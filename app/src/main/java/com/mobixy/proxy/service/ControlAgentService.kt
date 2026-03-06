package com.mobixy.proxy.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mobixy.proxy.R
import com.mobixy.proxy.core.constants.AppConstants
import com.mobixy.proxy.data.datasource.local.PrefsDataSource
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.UUID

class ControlAgentService : Service() {

    private val okHttpClient = OkHttpClient()

    @Volatile
    private var webSocket: WebSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connect()
            ACTION_DISCONNECT -> disconnect()
            else -> connect()
        }
        return START_STICKY
    }

    private fun connect() {
        if (webSocket != null) return

        val prefs = PrefsDataSource(applicationContext)
        prefs.setAgentConnected(false)

        if (prefs.getProxyCredentials() == null) {
            val u = randomId(10)
            val p = randomId(24)
            prefs.setProxyCredentials(u, p)
        }
        val host = prefs.getBackendHost() ?: return
        val enrollToken = prefs.getBackendEnrollToken() ?: return
        val deviceId = prefs.getDeviceId() ?: UUID.randomUUID().toString().also { prefs.setDeviceId(it) }

        try {
            startForeground(AppConstants.NOTIFICATION_ID + 2, createNotification(host))
        } catch (_: Throwable) {
            stopSelf()
            return
        }

        Thread {
            val jwt = ensureDeviceJwt(prefs, host, enrollToken, deviceId)
            val url = if (!jwt.isNullOrBlank()) {
                "ws://$host:${DEFAULT_PORT}/ws/device?deviceId=$deviceId&jwt=${encode(jwt)}"
            } else {
                "ws://$host:${DEFAULT_PORT}/ws/device?deviceId=$deviceId&token=$enrollToken"
            }

            val request = Request.Builder().url(url).build()

            webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "Connected")
                    PrefsDataSource(applicationContext).setAgentConnected(true)
                    sendStatus(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(webSocket, text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket failure", t)
                    PrefsDataSource(applicationContext).setAgentConnected(false)
                    disconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "Closed: $code $reason")
                    PrefsDataSource(applicationContext).setAgentConnected(false)
                    disconnect()
                }
            })
        }.start()
    }

    private fun ensureDeviceJwt(prefs: PrefsDataSource, host: String, enrollToken: String, deviceId: String): String? {
        val existing = prefs.getDeviceJwt()
        if (!existing.isNullOrBlank()) return existing

        val base = "http://$host:${DEFAULT_PORT}"

        val secret = prefs.getDeviceSecret() ?: run {
            val body = JSONObject()
            body.put("deviceId", deviceId)
            body.put("enrollToken", enrollToken)

            val req = Request.Builder()
                .url("$base/device/register")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val resp = runCatching { okHttpClient.newCall(req).execute() }.getOrNull() ?: return null
            resp.use {
                if (!it.isSuccessful) return null
                val txt = it.body?.string().orEmpty()
                val json = runCatching { JSONObject(txt) }.getOrNull() ?: return null
                val s = json.optString("secret").trim()
                if (s.isBlank()) return null
                prefs.setDeviceSecret(s)
                s
            }
        }

        val body = JSONObject()
        body.put("deviceId", deviceId)
        body.put("secret", secret)

        val req = Request.Builder()
            .url("$base/device/auth")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val resp = runCatching { okHttpClient.newCall(req).execute() }.getOrNull() ?: return null
        resp.use {
            if (!it.isSuccessful) return null
            val txt = it.body?.string().orEmpty()
            val json = runCatching { JSONObject(txt) }.getOrNull() ?: return null
            val t = json.optString("token").trim()
            if (t.isBlank()) return null
            prefs.setDeviceJwt(t)
            return t
        }
    }

    private fun encode(s: String): String {
        return java.net.URLEncoder.encode(s, "UTF-8")
    }

    private fun handleMessage(ws: WebSocket, text: String) {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (obj.optString("kind")) {
            "command" -> {
                val type = obj.optString("type")
                val id = obj.optString("id")
                val payload = obj.optJSONObject("payload")

                when (type) {
                    "proxy_start" -> startLocalSocks()
                    "proxy_stop" -> stopLocalSocks()
                    "set_credentials" -> {
                        val u = payload?.optString("username")?.trim().orEmpty()
                        val p = payload?.optString("password")?.trim().orEmpty()
                        if (u.isNotEmpty() && p.isNotEmpty()) {
                            PrefsDataSource(applicationContext).setProxyCredentials(u, p)
                        }
                    }
                    "get_status" -> {
                        // fallthrough to send status
                    }
                }

                val ack = JSONObject()
                ack.put("kind", "ack")
                if (id.isNotBlank()) ack.put("id", id)
                ack.put("type", type)
                ws.send(ack.toString())

                sendStatus(ws)

                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching { sendStatus(ws) }
                }, 500)
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching { sendStatus(ws) }
                }, 1500)
            }
            else -> {
            }
        }
    }

    private fun startLocalSocks() {
        val intent = Intent(this, LocalSocksProxyService::class.java)
            .setAction(LocalSocksProxyService.ACTION_START)
        startForegroundService(intent)
    }

    private fun stopLocalSocks() {
        val intent = Intent(this, LocalSocksProxyService::class.java)
            .setAction(LocalSocksProxyService.ACTION_STOP)
        startService(intent)
    }

    private fun sendStatus(ws: WebSocket) {
        val prefs = PrefsDataSource(applicationContext)
        val deviceId = prefs.getDeviceId()
        val creds = prefs.getProxyCredentials()
        val status = JSONObject()
        status.put("kind", "status")
        if (deviceId != null) status.put("deviceId", deviceId)
        status.put("socksPort", LocalSocksProxyService.DEFAULT_PORT)
        status.put("socksRunning", LocalSocksProxyService.isRunning)
        status.put("credsConfigured", creds != null)
        if (creds != null) {
            status.put("proxyUsername", creds.first)
            status.put("proxyPassword", creds.second)
        }
        getLanIpAddress()?.let { status.put("lanIp", it) }
        prefs.getFcmToken()?.let { status.put("fcmToken", it) }
        ws.send(status.toString())
    }

    private fun getLanIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull { it != null && !it.startsWith("127.") }
        } catch (_: Throwable) {
            null
        }
    }

    private fun randomId(len: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val rnd = SecureRandom()
        val sb = StringBuilder(len)
        repeat(len) {
            sb.append(chars[rnd.nextInt(chars.length)])
        }
        return sb.toString()
    }

    private fun disconnect() {
        runCatching { PrefsDataSource(applicationContext).setAgentConnected(false) }
        try {
            webSocket?.close(1000, "disconnect")
        } catch (_: Throwable) {
        }
        webSocket = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun createNotification(host: String): Notification {
        return NotificationCompat.Builder(this, AppConstants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Mobixy Control Agent")
            .setContentText("Connected to $host")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_CONNECT = "com.mobixy.proxy.service.ControlAgentService.CONNECT"
        const val ACTION_DISCONNECT = "com.mobixy.proxy.service.ControlAgentService.DISCONNECT"

        const val DEFAULT_PORT = 8787

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private const val TAG = "ControlAgentService"
    }
}
