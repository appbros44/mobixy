package com.mobixy.proxy.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mobixy.proxy.R
import com.mobixy.proxy.core.constants.AppConstants
import com.mobixy.proxy.tunnel.DeviceTunnelClient
import com.mobixy.proxy.tunnel.StreamMultiplexer
import okhttp3.OkHttpClient

class ProxyForegroundService : Service() {

    private var tunnelClient: DeviceTunnelClient? = null
    private var multiplexer: StreamMultiplexer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startAgent()
            ACTION_STOP -> stopAgent()
            else -> startAgent()
        }
        return START_STICKY
    }

    private fun startAgent() {
        if (tunnelClient != null) return

        startForeground(AppConstants.NOTIFICATION_ID, createNotification())

        val okHttpClient = OkHttpClient.Builder().build()
        multiplexer = StreamMultiplexer(applicationContext) { tunnelClient }

        // TODO: Replace with real deviceId/token provisioning via REST once server exists.
        val deviceId = "device-1"
        val deviceToken = "token-1"

        tunnelClient = DeviceTunnelClient(
            okHttpClient = okHttpClient,
            tunnelUrl = AppConstants.TUNNEL_URL,
            deviceId = deviceId,
            deviceToken = deviceToken,
            streamHandler = multiplexer!!
        ).also { it.connect() }
    }

    private fun stopAgent() {
        multiplexer?.shutdown()
        multiplexer = null
        tunnelClient?.close()
        tunnelClient = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopAgent()
        super.onDestroy()
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, AppConstants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Mobixy agent running")
            .setContentText("Maintaining device tunnel")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_START = "com.mobixy.proxy.service.ProxyForegroundService.START"
        const val ACTION_STOP = "com.mobixy.proxy.service.ProxyForegroundService.STOP"
    }
}