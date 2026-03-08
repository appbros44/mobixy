package com.mobixy.proxy.tunnel

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

internal class StreamMultiplexer(
    appContext: Context,
    private val tunnelClientProvider: () -> DeviceTunnelClient?
) : DeviceTunnelClient.StreamRelay {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dialer = CellularDialer(appContext)

    class TunnelStream(
        private val sid: String,
        private val host: String,
        private val port: Int
    ) : Closeable {
        private var socket: Socket? = null
        private var inputStream: InputStream? = null
        private var outputStream: OutputStream? = null

        fun connect(timeoutMs: Int) {
            val sock = Socket()
            sock.connect(java.net.InetSocketAddress(host, port), timeoutMs)
            socket = sock
            inputStream = sock.getInputStream()
            outputStream = sock.getOutputStream()
        }

        fun getInputStream(): InputStream {
            return inputStream ?: throw IllegalStateException("Not connected")
        }

        fun write(data: ByteArray) {
            outputStream?.write(data)
            outputStream?.flush()
        }

        override fun close() {
            try {
                inputStream?.close()
            } catch (_: Exception) {}
            try {
                outputStream?.close()
            } catch (_: Exception) {}
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    }

    private data class StreamState(
        val socket: Socket,
        val input: InputStream,
        val output: OutputStream
    )

    private val streams = ConcurrentHashMap<Int, StreamState>()

    override fun onOpenRequest(sid: Int, host: String, port: Int, timeoutMs: Int) {
        scope.launch {
            val tunnel = tunnelClientProvider()
            if (tunnel == null) {
                Log.w(TAG, "Tunnel not connected")
                return@launch
            }

            try {
                val socket = dialer.dial(host, port, timeoutMs)
                val state = StreamState(socket, socket.getInputStream(), socket.getOutputStream())
                streams[sid] = state
                tunnel.sendOpenOk(sid)

                // Relay socket -> tunnel
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = state.input.read(buffer)
                    if (read <= 0) break
                    tunnel.sendData(sid, buffer.copyOfRange(0, read))
                }
            } catch (t: Throwable) {
                tunnel.sendOpenFail(sid, t.message ?: "dial_failed")
            } finally {
                closeStreamInternal(sid, "socket_end")
            }
        }
    }

    override fun onRemoteData(sid: Int, payload: ByteArray) {
        val state = streams[sid] ?: return
        try {
            state.output.write(payload)
            state.output.flush()
        } catch (_: Throwable) {
            closeStreamInternal(sid, "write_failed")
        }
    }

    override fun onRemoteClose(sid: Int, reason: String) {
        closeStreamInternal(sid, reason)
    }

    private fun closeStreamInternal(sid: Int, reason: String) {
        val state = streams.remove(sid) ?: return
        try {
            state.socket.close()
        } catch (_: Throwable) {
        }

        tunnelClientProvider()?.sendClose(sid, reason)
    }

    fun shutdown() {
        streams.keys.forEach { closeStreamInternal(it, "shutdown") }
        scope.cancel()
    }

    private companion object {
        private const val TAG = "StreamMultiplexer"
    }
}
