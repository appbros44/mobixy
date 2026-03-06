package com.mobixy.proxy.presentation.common

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mobixy.proxy.service.ControlAgentService

fun startControlAgentConnect(context: Context) {
    val intent = Intent(context, ControlAgentService::class.java)
        .setAction(ControlAgentService.ACTION_CONNECT)
    ContextCompat.startForegroundService(context, intent)
}
