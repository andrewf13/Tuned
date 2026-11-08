package com.andrewf13.tuned.data

data class Account(val server: String, val username: String, val password: String)

data class Category(val id: String, val name: String)

data class Channel(
    val streamId: Int,
    val num: Int,
    val name: String,
    val icon: String?,
    val categoryId: String?,
    val epgId: String?,
)

data class Programme(
    val title: String,
    val description: String,
    val startMs: Long,
    val endMs: Long,
) {
    fun isOn(now: Long) = now in startMs until endMs
    fun progress(now: Long): Float =
        ((now - startMs).toFloat() / (endMs - startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
}

data class AccountInfo(
    val status: String?,
    val expiresMs: Long?,
    val maxConnections: String?,
    val activeConnections: String?,
)

class XtreamException(message: String) : Exception(message)
