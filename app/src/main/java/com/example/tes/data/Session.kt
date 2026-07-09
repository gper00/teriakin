package com.example.tes.data

data class Session(
    val startTimeMillis: Long,
    val durationSeconds: Int,
    val distractions: Int = 0,
    val completed: Boolean = true
) {
    val endTimeMillis: Long get() = startTimeMillis + (durationSeconds * 1000L)
}
