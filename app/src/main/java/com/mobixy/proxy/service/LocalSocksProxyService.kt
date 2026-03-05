package com.mobixy.proxy.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mobixy.proxy.R
import com.mobixy.proxy.core.constants.AppConstants
import com.mobixy.proxy.data.datasource.local.PrefsDataSource
import com.mobixy.proxy.localproxy.LocalSocks5Server

class LocalSocksProxyService : Service() {

    private var server: LocalSocks5Server? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProxy()
            ACTION_STOP -> stopProxy()
            else -> startProxy()
        }
        return START_STICKY
    }

    private fun startProxy() {
        if (server != null) return

        val prefs = PrefsDataSource(applicationContext)
        if (prefs.getProxyCredentials() == null) {
            stopSelf()
            return
        }

        try {
            startForeground(AppConstants.NOTIFICATION_ID + 1, createNotification())
        } catch (_: Throwable) {
            stopSelf()
            return
        }
        server = LocalSocks5Server(
            port = DEFAULT_PORT,
            credentialsProvider = { prefs.getProxyCredentials() }
        ).also { it.start() }
    }

    private fun stopProxy() {
        server?.stop()
        server = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopProxy()
        super.onDestroy()
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, AppConstants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Mobixy SOCKS5 running")
            .setContentText("Listening on port $DEFAULT_PORT")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_START = "com.mobixy.proxy.service.LocalSocksProxyService.START"
        const val ACTION_STOP = "com.mobixy.proxy.service.LocalSocksProxyService.STOP"
        const val DEFAULT_PORT = 1080
    }
}
