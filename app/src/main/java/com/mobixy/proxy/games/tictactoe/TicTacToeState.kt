package com.mobixy.proxy.games.tictactoe

data class TicTacToeState(
    val board: List<String> = List(9) { "" },
    val next: String = "X"
)

fun tttReset(): TicTacToeState = TicTacToeState()

fun tttPlay(state: TicTacToeState, index: Int): TicTacToeState {
    if (index !in 0..8) return state
    if (state.board[index].isNotBlank()) return state

    val nb = state.board.toMutableList()
    nb[index] = state.next

    return state.copy(
        board = nb.toList(),
        next = if (state.next == "X") "O" else "X"
    )
}
