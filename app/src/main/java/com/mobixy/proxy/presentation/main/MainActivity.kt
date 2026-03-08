package com.mobixy.proxy.presentation.main

import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.google.firebase.messaging.FirebaseMessaging
import com.mobixy.proxy.BuildConfig
import com.mobixy.proxy.R
import com.mobixy.proxy.data.datasource.local.PrefsDataSource
import com.mobixy.proxy.games.tictactoe.TicTacToeScreen
import com.mobixy.proxy.presentation.common.EnsurePostNotificationsPermission
import com.mobixy.proxy.presentation.common.startControlAgentConnect
import com.mobixy.proxy.ui.theme.MobixyTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    LaunchedEffect(Unit) {
        if (!previewMode) {
            if (prefs?.getBackendHost().isNullOrBlank()) {
                prefs?.setBackendHost(BuildConfig.DEFAULT_BACKEND_HOST)
            }
            if (prefs?.getBackendEnrollToken().isNullOrBlank()) {
                prefs?.setBackendEnrollToken(BuildConfig.DEFAULT_ENROLL_TOKEN)
            }
        }
    }

    EnsurePostNotificationsPermission(previewMode = previewMode)

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
                                Toast.makeText(context, "Coming soon", Toast.LENGTH_SHORT).show()
                            }
                        )

                        GameCard(
                            modifier = Modifier.weight(1f),
                            imageRes = R.drawable.game_two,
                            title = "TicTacToe",
                            onPlay = {
                                startControlAgentConnect(context)
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
                                    Toast.makeText(context, "Coming soon", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            GameCard(
                                imageRes = R.drawable.game_two,
                                title = "TicTacToe",
                                onPlay = {
                                    startControlAgentConnect(context)
                                    selectedGame = "TicTacToe"
                                }
                            )
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