package com.mobixy.proxy.games.tictactoe

fun tttWinnerOf(board: List<String>): String? {
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
        val a = board[ln[0]]
        val b = board[ln[1]]
        val c = board[ln[2]]
        if (a.isNotBlank() && a == b && b == c) return a
    }

    return null
}

fun tttIsDraw(board: List<String>, winner: String?): Boolean {
    return winner == null && board.all { it.isNotBlank() }
}
