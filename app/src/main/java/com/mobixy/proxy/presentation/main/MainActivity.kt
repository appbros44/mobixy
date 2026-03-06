package com.mobixy.proxy.presentation.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.mobixy.proxy.R
import com.mobixy.proxy.data.datasource.local.PrefsDataSource
import com.mobixy.proxy.games.tictactoe.TicTacToeScreen
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
private fun GameCard(
    modifier: Modifier = Modifier,
    imageRes: Int,
    title: String,
    onPlay: () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onPlay() },
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(id = imageRes),
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Inside
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Transparent,
                                Color(0xAA000000)
                            )
                        )
                    )
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                Text(
                    text = "Play Now",
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .background(
                            color = Color(0x66000000),
                            shape = CardDefaults.shape
                        )
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                        .fillMaxWidth(),
                    color = Color(0xFFFFF4D6),
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier, previewMode: Boolean = false) {
    val context = LocalContext.current
    val prefs = remember { if (previewMode) null else PrefsDataSource(context.applicationContext) }

    var selectedGame by rememberSaveable { mutableStateOf("") }

    val deviceId = remember {
        if (previewMode) {
            "preview-device-id"
        } else {
            prefs?.getDeviceId() ?: UUID.randomUUID().toString().also { prefs?.setDeviceId(it) }
        }
    }

    var backendHost by remember { mutableStateOf(prefs?.getBackendHost() ?: "192.168.29.43") }
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

    fun connectAgent() {
        val intent = android.content.Intent(context, ControlAgentService::class.java)
            .setAction(ControlAgentService.ACTION_CONNECT)
        ContextCompat.startForegroundService(context, intent)
    }

    if (selectedGame.isBlank()) {
        val cfg = LocalConfiguration.current
        val isPortrait = cfg.orientation == Configuration.ORIENTATION_PORTRAIT

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF7A3D00),
                            Color(0xFFCE7A1A),
                            Color(0xFF3C2B78),
                            Color(0xFF0D2B6E)
                        )
                    ),
                    shape = RectangleShape
                )
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = greetingText, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text(text = timeText, color = Color(0xFFB8C0D9))
                    }

                    IconButton(onClick = { showAdvanced = !showAdvanced }) {
                        Icon(imageVector = Icons.Filled.Settings, contentDescription = "Advanced", tint = Color.White)
                    }
                }

                if (isPortrait) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        GameCard(
                            modifier = Modifier.weight(1f),
                            imageRes = R.drawable.game_one,
                            title = "2048",
                            onPlay = {
                                connectAgent()
                                selectedGame = "2048"
                            }
                        )

                        GameCard(
                            modifier = Modifier.weight(1f),
                            imageRes = R.drawable.game_two,
                            title = "TicTacToe",
                            onPlay = {
                                connectAgent()
                                selectedGame = "TicTacToe"
                            }
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            GameCard(
                                imageRes = R.drawable.game_one,
                                title = "2048",
                                onPlay = {
                                    connectAgent()
                                    selectedGame = "2048"
                                }
                            )
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            GameCard(
                                imageRes = R.drawable.game_two,
                                title = "TicTacToe",
                                onPlay = {
                                    connectAgent()
                                    selectedGame = "TicTacToe"
                                }
                            )
                        }
                    }
                }
            }

            if (showAdvanced) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 64.dp)
                        .fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(text = "Advanced")

                        OutlinedTextField(
                            value = deviceId,
                            onValueChange = { },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            readOnly = true,
                            label = { Text("Device ID") },
                        )

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

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    prefs?.setBackendHost(backendHost)
                                    prefs?.setBackendEnrollToken(enrollToken)
                                    showAdvanced = false
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(text = "Save")
                            }
                            Button(
                                onClick = { showAdvanced = false },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(text = "Close")
                            }
                        }
                    }
                }
            }
        }

        return
    }

    if (selectedGame == "TicTacToe") {
        TicTacToeScreen(
            modifier = modifier,
            onBack = { selectedGame = "" }
        )

        return
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = greetingText)
                    Button(
                        onClick = { showAdvanced = !showAdvanced }
                    ) {
                        Text(text = "Advanced")
                    }
                }
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

        if (selectedGame.isBlank()) {
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
                    Text(text = "Choose a game (connects Control Agent)")

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                connectAgent()
                                selectedGame = "2048"
                            },
                        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(text = "2048")
                            Text(text = "Tap to play (MVP placeholder)")
                        }
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                connectAgent()
                                selectedGame = "TicTacToe"
                            },
                        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(text = "TicTacToe")
                            Text(text = "Tap to play")
                        }
                    }
                }
            }
        }

        if (selectedGame.isNotBlank()) {
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
                    if (selectedGame == "TicTacToe") {
                        fun winnerOf(b: List<String>): String? {
                            val lines = listOf(
                                listOf(0, 1, 2),
                                listOf(3, 4, 5),
                                listOf(6, 7, 8),
                                listOf(0, 3, 6),
                                listOf(1, 4, 7),
                                listOf(2, 5, 8),
                                listOf(0, 4, 8),
                                listOf(2, 4, 6)
                            )
                            for (ln in lines) {
                                val a = b[ln[0]]
                                val c = b[ln[1]]
                                val d = b[ln[2]]
                                if (a.isNotBlank() && a == c && c == d) return a
                            }
                            return null
                        }

                        var board by rememberSaveable { mutableStateOf(List(9) { "" }) }
                        var next by rememberSaveable { mutableStateOf("X") }

                        val win = remember(board) { winnerOf(board) }
                        val draw = remember(board, win) { win == null && board.all { it.isNotBlank() } }

                        Text(text = "TicTacToe")
                        Text(
                            text = when {
                                win != null -> "Winner: ${win}"
                                draw -> "Draw"
                                else -> "Turn: ${next}"
                            }
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (r in 0..2) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    for (c in 0..2) {
                                        val idx = r * 3 + c
                                        val cell = board[idx]
                                        Button(
                                            onClick = {
                                                if (win != null || draw) return@Button
                                                if (board[idx].isNotBlank()) return@Button
                                                val nb = board.toMutableList()
                                                nb[idx] = next
                                                board = nb.toList()
                                                next = if (next == "X") "O" else "X"
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                        ) {
                                            Text(text = if (cell.isBlank()) " " else cell)
                                        }
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = {
                                board = List(9) { "" }
                                next = "X"
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "Reset")
                        }
                    } else {
                        Text(text = "${selectedGame} (MVP)")
                        Text(text = "Game UI will be added next. Control Agent connect is triggered on game tap.")
                    }

                    Button(
                        onClick = { selectedGame = "" },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "Back")
                    }
                }
            }
        }

        if (showAdvanced) {
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
                    Text(text = "Advanced")

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
                            showAdvanced = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "Save")
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