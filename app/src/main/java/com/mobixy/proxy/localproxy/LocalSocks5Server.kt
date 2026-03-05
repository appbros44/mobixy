package com.mobixy.proxy.localproxy

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

internal class LocalSocks5Server(
    private val port: Int,
    private val credentialsProvider: () -> Pair<String, String>?,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    fun start() {
        if (acceptJob != null) return

        serverSocket = ServerSocket(port)
        acceptJob = scope.launch {
            Log.i(TAG, "SOCKS5 server listening on :$port")
            while (true) {
                val client = try {
                    serverSocket?.accept()
                } catch (t: Throwable) {
                    null
                } ?: break

                scope.launch {
                    handleClient(client)
                }
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null

        acceptJob?.cancel()
        acceptJob = null

        scope.cancel()
    }

    private suspend fun handleClient(client: Socket) {
        client.use {
            val input = DataInputStream(BufferedInputStream(client.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(client.getOutputStream()))

            // 1) Method selection
            val ver = input.readUnsignedByte()
            if (ver != 0x05) return

            val nMethods = input.readUnsignedByte()
            val methods = ByteArray(nMethods)
            input.readFully(methods)

            val configuredCreds = credentialsProvider()
            val chosenMethod = chooseAuthMethod(methods, configuredCreds)
            if (chosenMethod == METHOD_NO_ACCEPTABLE) {
                output.writeByte(0x05)
                output.writeByte(METHOD_NO_ACCEPTABLE)
                output.flush()
                return
            }

            output.writeByte(0x05)
            output.writeByte(chosenMethod)
            output.flush()

            val ok = handleUsernamePasswordAuth(input, output, configuredCreds)
            if (!ok) return

            // 2) CONNECT request
            val reqVer = input.readUnsignedByte()
            if (reqVer != 0x05) return

            val cmd = input.readUnsignedByte()
            input.readUnsignedByte() // RSV
            val atyp = input.readUnsignedByte()

            if (cmd != 0x01) {
                writeReply(output, reply = 0x07) // Command not supported
                return
            }

            val dstHost = when (atyp) {
                0x01 -> { // IPv4
                    val addr = ByteArray(4)
                    input.readFully(addr)
                    InetAddress.getByAddress(addr).hostAddress ?: return
                }

                0x03 -> { // Domain
                    val len = input.readUnsignedByte()
                    val nameBytes = ByteArray(len)
                    input.readFully(nameBytes)
                    String(nameBytes, StandardCharsets.UTF_8)
                }

                0x04 -> { // IPv6
                    val addr = ByteArray(16)
                    input.readFully(addr)
                    InetAddress.getByAddress(addr).hostAddress ?: return
                }

                else -> {
                    writeReply(output, reply = 0x08) // Address type not supported
                    return
                }
            }

            val dstPort = input.readUnsignedShort()

            val remote = try {
                Socket(dstHost, dstPort)
            } catch (t: Throwable) {
                writeReply(output, reply = 0x05) // Connection refused
                return
            }

            remote.use { upstream ->
                writeReply(output, reply = 0x00, bound = upstream.localAddress, boundPort = upstream.localPort)

                val clientIn = input
                val clientOut = output

                val upstreamIn = DataInputStream(BufferedInputStream(upstream.getInputStream()))
                val upstreamOut = DataOutputStream(BufferedOutputStream(upstream.getOutputStream()))

                val job1 = scope.launch {
                    pipe(clientIn, upstreamOut)
                }
                val job2 = scope.launch {
                    pipe(upstreamIn, clientOut)
                }

                job1.join()
                job2.join()
            }
        }
    }

    private suspend fun pipe(input: DataInputStream, output: DataOutputStream) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: Throwable) {
        }
    }

    private fun writeReply(
        output: DataOutputStream,
        reply: Int,
        bound: InetAddress = InetAddress.getLoopbackAddress(),
        boundPort: Int = 0,
    ) {
        output.writeByte(0x05) // VER
        output.writeByte(reply) // REP
        output.writeByte(0x00) // RSV

        val addr = bound.address
        when (addr.size) {
            4 -> {
                output.writeByte(0x01)
                output.write(addr)
            }

            16 -> {
                output.writeByte(0x04)
                output.write(addr)
            }

            else -> {
                // fallback: ipv4 0.0.0.0
                output.writeByte(0x01)
                output.write(byteArrayOf(0, 0, 0, 0))
            }
        }

        output.writeByte((boundPort shr 8) and 0xFF)
        output.writeByte(boundPort and 0xFF)
        output.flush()
    }

    private companion object {
        private const val TAG = "LocalSocks5Server"

        private const val METHOD_NO_AUTH: Int = 0x00
        private const val METHOD_USERNAME_PASSWORD: Int = 0x02
        private const val METHOD_NO_ACCEPTABLE: Int = 0xFF

        private const val AUTH_VERSION: Int = 0x01
        private const val AUTH_STATUS_SUCCESS: Int = 0x00
        private const val AUTH_STATUS_FAILURE: Int = 0x01
    }

    private fun chooseAuthMethod(clientMethods: ByteArray, configuredCreds: Pair<String, String>?): Int {
        if (configuredCreds == null) return METHOD_NO_ACCEPTABLE
        val supportsUserPass = clientMethods.any { (it.toInt() and 0xFF) == METHOD_USERNAME_PASSWORD }
        return if (supportsUserPass) METHOD_USERNAME_PASSWORD else METHOD_NO_ACCEPTABLE
    }

    private fun handleUsernamePasswordAuth(
        input: DataInputStream,
        output: DataOutputStream,
        configuredCreds: Pair<String, String>?
    ): Boolean {
        // RFC 1929:
        // +----+------+----------+------+----------+
        // |VER | ULEN |  UNAME   | PLEN |  PASSWD  |
        // +----+------+----------+------+----------+
        val ver = input.readUnsignedByte()
        if (ver != AUTH_VERSION) {
            writeAuthStatus(output, ok = false)
            return false
        }

        val ulen = input.readUnsignedByte()
        val unameBytes = ByteArray(ulen)
        input.readFully(unameBytes)
        val username = String(unameBytes, StandardCharsets.UTF_8)

        val plen = input.readUnsignedByte()
        val passBytes = ByteArray(plen)
        input.readFully(passBytes)
        val password = String(passBytes, StandardCharsets.UTF_8)

        val ok = configuredCreds != null &&
            username == configuredCreds.first &&
            password == configuredCreds.second

        writeAuthStatus(output, ok)
        return ok
    }

    private fun writeAuthStatus(output: DataOutputStream, ok: Boolean) {
        output.writeByte(AUTH_VERSION)
        output.writeByte(if (ok) AUTH_STATUS_SUCCESS else AUTH_STATUS_FAILURE)
        output.flush()
    }
}
