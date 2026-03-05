package com.mobixy.proxy.fcm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import androidx.core.content.ContextCompat
import com.mobixy.proxy.data.datasource.local.PrefsDataSource
import com.mobixy.proxy.service.ControlAgentService
import android.content.Intent

class MobixyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        PrefsDataSource(applicationContext).setFcmToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val kind = message.data["kind"]?.trim()
        if (kind != "wake") return

        runCatching {
            val i = Intent(applicationContext, ControlAgentService::class.java)
                .setAction(ControlAgentService.ACTION_CONNECT)
            ContextCompat.startForegroundService(applicationContext, i)
        }
    }
}
