package com.mobixy.proxy.presentation.main

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.mobixy.proxy.data.datasource.local.PrefsDataSource
import com.mobixy.proxy.service.ControlAgentService
import com.mobixy.proxy.service.LocalSocksProxyService
import com.mobixy.proxy.service.ProxyForegroundService
import com.mobixy.proxy.ui.theme.MobixyTheme
import java.net.Inet4Address
import java.net.NetworkInterface
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
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { PrefsDataSource(context.applicationContext) }

    var username by remember { mutableStateOf(prefs.getProxyCredentials()?.first.orEmpty()) }
    var password by remember { mutableStateOf(prefs.getProxyCredentials()?.second.orEmpty()) }
    var savedCreds by remember { mutableStateOf(prefs.getProxyCredentials()) }

    var backendHost by remember {
        mutableStateOf(prefs.getBackendHost() ?: "192.168.29.44")
    }
    var enrollToken by remember {
        mutableStateOf(prefs.getBackendEnrollToken() ?: "dev-enroll-token")
    }
    var deviceId by remember {
        mutableStateOf(prefs.getDeviceId() ?: UUID.randomUUID().toString())
    }

    var hasNotificationPermission by remember {
        mutableStateOf(
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        )
    }

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

    LaunchedEffect(Unit) {
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    if (!token.isNullOrBlank()) {
                        prefs.setFcmToken(token)
                    }
                }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "Mobixy")

        var ip by remember { mutableStateOf(getLanIpAddress(context)) }

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

        Text(text = "SOCKS5: ${ip ?: "(no network)"}:${LocalSocksProxyService.DEFAULT_PORT}")

        OutlinedTextField(
            value = backendHost,
            onValueChange = { backendHost = it },
            singleLine = true,
            label = { Text("Backend Host") },
        )

        OutlinedTextField(
            value = enrollToken,
            onValueChange = { enrollToken = it },
            singleLine = true,
            label = { Text("Enroll Token") },
        )

        OutlinedTextField(
            value = deviceId,
            onValueChange = { deviceId = it },
            singleLine = true,
            label = { Text("Device ID") },
        )

        Button(
            onClick = {
                prefs.setBackendHost(backendHost)
                prefs.setBackendEnrollToken(enrollToken)
                prefs.setDeviceId(deviceId)
            }
        ) {
            Text(text = "Save Backend Settings")
        }

        val backendConfigured = remember(backendHost, enrollToken, deviceId) {
            backendHost.trim().isNotEmpty() && enrollToken.trim().isNotEmpty() && deviceId.trim().isNotEmpty()
        }

        Button(
            onClick = {
                prefs.setBackendHost(backendHost)
                prefs.setBackendEnrollToken(enrollToken)
                prefs.setDeviceId(deviceId)
                val intent = android.content.Intent(context, ControlAgentService::class.java)
                    .setAction(ControlAgentService.ACTION_CONNECT)
                ContextCompat.startForegroundService(context, intent)
            },
            enabled = backendConfigured && hasNotificationPermission
        ) {
            Text(text = "Connect Control Agent")
        }

        Button(
            onClick = {
                val intent = android.content.Intent(context, ControlAgentService::class.java)
                    .setAction(ControlAgentService.ACTION_DISCONNECT)
                context.startService(intent)
            }
        ) {
            Text(text = "Disconnect Control Agent")
        }

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            singleLine = true,
            label = { Text("SOCKS5 Username") },
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            label = { Text("SOCKS5 Password") },
        )

        Button(
            onClick = {
                prefs.setProxyCredentials(username.trim(), password.trim())
                savedCreds = prefs.getProxyCredentials()
                username = ""
                password = ""
            }
        ) {
            Text(text = "Save Credentials")
        }

        val credsConfigured = remember(savedCreds) { savedCreds != null }

        Button(
            onClick = {
                val intent = android.content.Intent(context, LocalSocksProxyService::class.java)
                    .setAction(LocalSocksProxyService.ACTION_START)
                ContextCompat.startForegroundService(context, intent)
            },
            enabled = credsConfigured && hasNotificationPermission
        ) {
            Text(text = "Start Local SOCKS5 (1080)")
        }

        Button(
            onClick = {
                val intent = android.content.Intent(context, LocalSocksProxyService::class.java)
                    .setAction(LocalSocksProxyService.ACTION_STOP)
                context.startService(intent)
            }
        ) {
            Text(text = "Stop Local SOCKS5")
        }

        Button(
            onClick = {
                val intent = android.content.Intent(context, ProxyForegroundService::class.java)
                    .setAction(ProxyForegroundService.ACTION_START)
                ContextCompat.startForegroundService(context, intent)
            }
        ) {
            Text(text = "Start Tunnel Agent (later phases)")
        }

        Button(
            onClick = {
                val intent = android.content.Intent(context, ProxyForegroundService::class.java)
                    .setAction(ProxyForegroundService.ACTION_STOP)
                context.startService(intent)
            }
        ) {
            Text(text = "Stop Tunnel Agent")
        }
    }
}