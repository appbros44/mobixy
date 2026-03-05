package com.mobixy.proxy.tunnel

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

internal class DeviceTunnelClient(
    private val okHttpClient: OkHttpClient,
    private val tunnelUrl: String,
    private val deviceId: String,
    private val deviceToken: String,
    private val streamHandler: StreamHandler
) : Closeable {

    interface StreamHandler {
        fun onOpenRequest(sid: Int, host: String, port: Int, timeoutMs: Int)
        fun onRemoteClose(sid: Int, reason: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var webSocket: WebSocket? = null

    private val pendingOpens = ConcurrentHashMap<Int, PendingOpen>()

    fun connect() {
        val request = Request.Builder().url(tunnelUrl).build()
        okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@DeviceTunnelClient.webSocket = webSocket
                sendHello()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleTextMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleBinaryMessage(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
            }
        })
    }

    private fun sendHello() {
        val hello = JSONObject()
        hello.put("t", "hello")
        hello.put("deviceId", deviceId)
        hello.put("token", deviceToken)
        val cap = JSONObject()
        cap.put("cellularBind", true)
        hello.put("cap", cap)
        webSocket?.send(hello.toString())
    }

    fun sendOpenOk(sid: Int) {
        val msg = JSONObject()
        msg.put("t", "open_ok")
        msg.put("sid", sid)
        webSocket?.send(msg.toString())
    }

    fun sendOpenFail(sid: Int, error: String) {
        val msg = JSONObject()
        msg.put("t", "open_fail")
        msg.put("sid", sid)
        msg.put("error", error)
        webSocket?.send(msg.toString())
    }

    fun sendClose(sid: Int, reason: String) {
        val msg = JSONObject()
        msg.put("t", "close")
        msg.put("sid", sid)
        msg.put("reason", reason)
        webSocket?.send(msg.toString())
    }

    fun sendData(sid: Int, payload: ByteArray) {
        val frame = TunnelFrames.encode(sid = sid, payload = payload)
        webSocket?.send(ByteString.of(*frame))
    }

    private fun handleTextMessage(text: String) {
        val obj = JSONObject(text)
        when (val type = obj.optString("t")) {
            "open" -> {
                val sid = obj.getInt("sid")
                val host = obj.getString("host")
                val port = obj.getInt("port")
                val timeoutMs = obj.optInt("timeoutMs", 10_000)
                scope.launch {
                    streamHandler.onOpenRequest(sid, host, port, timeoutMs)
                }
            }

            "close" -> {
                val sid = obj.getInt("sid")
                val reason = obj.optString("reason", "remote_close")
                streamHandler.onRemoteClose(sid, reason)
            }

            "open_ok", "open_fail" -> {
                val sid = obj.getInt("sid")
                pendingOpens[sid]?.let { pending ->
                    if (type == "open_ok") {
                        pending.result.complete(true)
                    } else {
                        pending.error = obj.optString("error")
                        pending.result.complete(false)
                    }
                    pendingOpens.remove(sid)
                }
            }

            else -> {
                Log.w(TAG, "Unknown message type: $type")
            }
        }
    }

    private fun handleBinaryMessage(bytes: ByteArray) {
        val decoded = TunnelFrames.decode(bytes)
        (streamHandler as? StreamRelay)?.onRemoteData(decoded.sid, decoded.payload)
    }

    override fun close() {
        try {
            webSocket?.close(1000, "closing")
        } catch (_: Exception) {
        }
        scope.cancel()
    }

    interface StreamRelay : StreamHandler {
        fun onRemoteData(sid: Int, payload: ByteArray)
    }

    private companion object {
        private const val TAG = "DeviceTunnelClient"
    }
}
