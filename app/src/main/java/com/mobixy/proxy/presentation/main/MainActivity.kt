package com.mobixy.proxy.presentation.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.mobixy.proxy.data.datasource.local.PrefsDataSource
import com.mobixy.proxy.service.ControlAgentService
import com.mobixy.proxy.service.LocalSocksProxyService
import com.mobixy.proxy.ui.theme.MobixyTheme
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MobixyTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

private fun getLanIpAddress(context: android.content.Context): String? {
    return try {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(active) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return null
        }

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

@Composable
fun MainScreen(modifier: Modifier = Modifier, previewMode: Boolean = false) {
    val context = LocalContext.current
    val prefs = remember { if (previewMode) null else PrefsDataSource(context.applicationContext) }

    var username by remember { mutableStateOf(prefs?.getProxyCredentials()?.first.orEmpty()) }
    var password by remember { mutableStateOf(prefs?.getProxyCredentials()?.second.orEmpty()) }
    var savedCreds by remember { mutableStateOf(prefs?.getProxyCredentials()) }
    var credsSavedAtMs by remember { mutableStateOf(if (savedCreds != null) System.currentTimeMillis() else 0L) }

    val deviceId = remember {
        if (previewMode) {
            "preview-device-id"
        } else {
            prefs?.getDeviceId() ?: UUID.randomUUID().toString().also { prefs?.setDeviceId(it) }
        }
    }

    var backendHost by remember { mutableStateOf(prefs?.getBackendHost() ?: "192.168.29.44") }
    var enrollToken by remember { mutableStateOf(prefs?.getBackendEnrollToken() ?: "dev-enroll-token") }

    var showAdvanced by remember { mutableStateOf(false) }

    var hasNotificationPermission by remember {
        mutableStateOf(
            previewMode ||
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        )
    }

    if (!previewMode) {
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            onResult = { granted ->
                hasNotificationPermission = granted
            }
        )

        LaunchedEffect(Unit) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    if (!previewMode) {
        LaunchedEffect(Unit) {
            runCatching {
                FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { token ->
                        if (!token.isNullOrBlank()) {
                            prefs?.setFcmToken(token)
                        }
                    }
            }
        }
    } else {
        LaunchedEffect(Unit) {
            // Do nothing in preview mode
        }
    }

    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    val epochDayNow = remember(nowMs) { nowMs / 86_400_000L }
    var dayStreak by remember { mutableStateOf(if (previewMode) 7 else prefs?.getDayStreakCount() ?: 0) }

    if (!previewMode) {
        LaunchedEffect(epochDayNow) {
            val last = prefs?.getLastOpenEpochDay()
            val current = epochDayNow
            if (last == null) {
                prefs?.setLastOpenEpochDay(current)
                prefs?.setDayStreakCount(1)
                dayStreak = 1
            } else if (last == current) {
                dayStreak = prefs?.getDayStreakCount() ?: 0
            } else if (last == current - 1L) {
                val next = (prefs?.getDayStreakCount() ?: 0) + 1
                prefs?.setLastOpenEpochDay(current)
                prefs?.setDayStreakCount(next)
                dayStreak = next
            } else {
                prefs?.setLastOpenEpochDay(current)
                prefs?.setDayStreakCount(1)
                dayStreak = 1
            }
        }
    }

    val timeText = remember(nowMs) {
        try {
            SimpleDateFormat("EEE, dd MMM yyyy  HH:mm:ss", Locale.getDefault()).format(Date(nowMs))
        } catch (_: Throwable) {
            ""
        }
    }

    val greetingText = remember(nowMs) {
        val h = try {
            java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        } catch (_: Throwable) {
            12
        }
        when (h) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Hello"
        }
    }

    val credsSaved = remember(savedCreds) { savedCreds != null }
    val credsSavedText = remember(credsSavedAtMs) {
        if (credsSavedAtMs <= 0L) return@remember ""
        try {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(credsSavedAtMs))
        } catch (_: Throwable) {
            ""
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(text = greetingText)
                Text(text = timeText)
                Text(text = "Day streak: ${if (dayStreak > 0) dayStreak else 0}")
            }
        }

        var ip by remember { mutableStateOf(getLanIpAddress(context)) }

        if (!previewMode) {
            DisposableEffect(Unit) {
                val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        ip = getLanIpAddress(context)
                    }

                    override fun onLost(network: android.net.Network) {
                        ip = getLanIpAddress(context)
                    }

                    override fun onCapabilitiesChanged(
                        network: android.net.Network,
                        networkCapabilities: NetworkCapabilities
                    ) {
                        ip = getLanIpAddress(context)
                    }
                }

                val request = NetworkRequest.Builder().build()
                cm.registerNetworkCallback(request, callback)

                onDispose {
                    try {
                        cm.unregisterNetworkCallback(callback)
                    } catch (_: Throwable) {
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = "SOCKS5: ${ip ?: "(no network)"}:${LocalSocksProxyService.DEFAULT_PORT}")

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("SOCKS5 Username") },
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("SOCKS5 Password") },
                )

                if (credsSaved) {
                    Text(text = if (credsSavedText.isNotBlank()) "Saved (${credsSavedText})" else "Saved")
                }

                Button(
                    onClick = {
                        val u = username.trim()
                        val p = password.trim()
                        if (u.isNotEmpty() && p.isNotEmpty()) {
                            prefs?.setProxyCredentials(u, p)
                            savedCreds = prefs?.getProxyCredentials() ?: (u to p)
                            credsSavedAtMs = System.currentTimeMillis()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Save Credentials")
                }
            }
        }

        if (credsSaved) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    var agentConnected by remember { mutableStateOf(if (previewMode) true else prefs?.isAgentConnected() == true) }
                    var selectedAgentAction by rememberSaveable {
                        mutableStateOf(
                            if (previewMode) "connect" else (prefs?.getAgentSelectedAction() ?: "")
                        )
                    }

                    LaunchedEffect(Unit) {
                        if (previewMode) return@LaunchedEffect
                        while (true) {
                            agentConnected = prefs?.isAgentConnected() == true
                            delay(750)
                        }
                    }

                    val effectiveSelected = remember(agentConnected, selectedAgentAction) {
                        if (selectedAgentAction.isNotBlank()) selectedAgentAction
                        else if (agentConnected) "connect" else "disconnect"
                    }

                    Text(text = if (agentConnected) "Control Agent: Connected" else "Control Agent: Disconnected")

                    val backendConfigured = remember(backendHost, enrollToken) {
                        backendHost.trim().isNotEmpty() && enrollToken.trim().isNotEmpty()
                    }

                    Button(
                        onClick = {
                            prefs?.setBackendHost(backendHost)
                            prefs?.setBackendEnrollToken(enrollToken)
                            prefs?.setAgentSelectedAction("connect")
                            selectedAgentAction = "connect"
                            val intent = android.content.Intent(context, ControlAgentService::class.java)
                                .setAction(ControlAgentService.ACTION_CONNECT)
                            ContextCompat.startForegroundService(context, intent)
                        },
                        enabled = backendConfigured && hasNotificationPermission,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = if (effectiveSelected == "connect") "Connect Control Agent (selected)" else "Connect Control Agent")
                    }

                    Button(
                        onClick = {
                            prefs?.setAgentSelectedAction("disconnect")
                            selectedAgentAction = "disconnect"
                            val intent = android.content.Intent(context, ControlAgentService::class.java)
                                .setAction(ControlAgentService.ACTION_DISCONNECT)
                            context.startService(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = if (effectiveSelected == "disconnect") "Disconnect Control Agent (selected)" else "Disconnect Control Agent")
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            showAdvanced = !showAdvanced
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = if (showAdvanced) "Hide Advanced" else "Show Advanced")
                    }

                    if (showAdvanced) {
                        OutlinedTextField(
                            value = deviceId,
                            onValueChange = { },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            readOnly = true,
                            label = { Text("Device ID") },
                        )

                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("deviceId", deviceId))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "Copy Device ID")
                        }

                        OutlinedTextField(
                            value = backendHost,
                            onValueChange = { backendHost = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Backend Host") },
                        )

                        OutlinedTextField(
                            value = enrollToken,
                            onValueChange = { enrollToken = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Enroll Token") },
                        )

                        Button(
                            onClick = {
                                prefs?.setBackendHost(backendHost)
                                prefs?.setBackendEnrollToken(enrollToken)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "Save Backend Settings")
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    MobixyTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            MainScreen(modifier = Modifier.padding(innerPadding), previewMode = true)
        }
    }
}