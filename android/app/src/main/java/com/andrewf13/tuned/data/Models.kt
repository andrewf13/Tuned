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
    /** Direct stream URL (M3U playlists). Xtream channels build theirs from the account. */
    val url: String? = null,
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

enum class SourceType { XTREAM, M3U }

/** One provider login. Favourites, groups and recents are kept per profile. */
data class Profile(
    val id: String,
    val name: String,
    val type: SourceType,
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
) {
    val account: Account get() = Account(server, username, password)
    val summary: String
        get() = when (type) {
            SourceType.XTREAM -> "Xtream Codes · $username @ ${server.removePrefix("http://").removePrefix("https://")}"
            SourceType.M3U -> "M3U playlist · " + m3uUrl.removePrefix("http://").removePrefix("https://").substringBefore('?').take(60)
        }
}
