package com.mobixy.proxy.games.tictactoe

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mobixy.proxy.ui.theme.MobixyTheme

private val TicTacToeStateSaver: Saver<TicTacToeState, Any> = listSaver(
    save = { listOf(it.board, it.next) },
    restore = {
        val board = it[0] as List<String>
        val next = it[1] as String
        TicTacToeState(board = board, next = next)
    }
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TicTacToeScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    var state by rememberSaveable(stateSaver = TicTacToeStateSaver) { mutableStateOf(TicTacToeState()) }
    var showWinDialog by rememberSaveable { mutableStateOf(false) }

    val win = remember(state.board) { tttWinnerOf(state.board) }
    val draw = remember(state.board, win) { tttIsDraw(state.board, win) }

    LaunchedEffect(win) {
        if (win != null) showWinDialog = true
    }

    val resetGame = {
        state = tttReset()
        showWinDialog = false
    }

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
                )
            )
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(text = "TicTacToe", color = Color.White) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White
                    ),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                    }
                )
            }
        ) { inner ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = when {
                        win != null -> "Result: ${win} wins"
                        draw -> "Result: Draw"
                        else -> "Turn: ${state.next}"
                    },
                    color = Color(0xFFEAF2FF),
                    fontWeight = FontWeight.SemiBold
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    for (r in 0..2) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            for (c in 0..2) {
                                val idx = r * 3 + c
                                val cell = state.board[idx]

                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    onClick = {
                                        if (win != null || draw) return@Card
                                        state = tttPlay(state, idx)
                                    },
                                    enabled = win == null && !draw,
                                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF102B5A))
                                ) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = cell,
                                            color = if (cell == "X") Color(0xFFFFD54F) else Color(0xFF81D4FA),
                                            fontWeight = FontWeight.ExtraBold,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = resetGame,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = "Reset")
                    }
                    Button(
                        onClick = onBack,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = "Back")
                    }
                }
            }
        }

        if (showWinDialog && win != null) {
            AlertDialog(
                onDismissRequest = { showWinDialog = false },
                title = { Text(text = "Congratulations!") },
                text = { Text(text = "${win} wins the game.") },
                confirmButton = {
                    TextButton(onClick = resetGame) {
                        Text(text = "Play again")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showWinDialog = false }) {
                        Text(text = "Close")
                    }
                }
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TicTacToeScreenPreview() {
    MobixyTheme {
        TicTacToeScreen(onBack = {})
    }
}
