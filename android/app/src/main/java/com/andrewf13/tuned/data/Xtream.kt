package com.andrewf13.tuned.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Turns whatever the user typed into an [Account]. Accepts "host:port",
 * "http://host:port", or a full get.php / player_api.php link with the
 * username and password in its query string.
 */
object AccountParser {
    fun parse(serverInput: String, user: String, pass: String): Account {
        var s = serverInput.trim()
        if (s.isEmpty()) throw XtreamException("Enter your server address")
        if (!s.contains("://")) s = "http://$s"
        val url = s.toHttpUrlOrNull() ?: throw XtreamException("That server address doesn't look right")
        val u = url.queryParameter("username") ?: user.trim()
        val p = url.queryParameter("password") ?: pass.trim()
        if (u.isEmpty() || p.isEmpty()) throw XtreamException("Enter your username and password")
        val base = url.newBuilder().query(null).encodedPath("/").build().toString().trimEnd('/')
        return Account(base, u, p)
    }
}

class XtreamClient(private val http: OkHttpClient, val account: Account) {

    private fun apiUrl(action: String?, vararg extra: Pair<String, String>): HttpUrl {
        val b = "${account.server}/player_api.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", account.username)
            .addQueryParameter("password", account.password)
        if (action != null) b.addQueryParameter("action", action)
        extra.forEach { b.addQueryParameter(it.first, it.second) }
        return b.build()
    }

    private suspend fun fetch(url: HttpUrl): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw XtreamException("Server replied ${r.code}")
            r.body?.bytes() ?: throw XtreamException("Empty reply from server")
        }
    }

    suspend fun authenticate(): AccountInfo {
        val root = XtreamParse.json.parseToJsonElement(fetch(apiUrl(null)).decodeToString())
        val info = (root as? JsonObject)?.get("user_info") as? JsonObject
            ?: throw XtreamException("This doesn't look like an Xtream Codes server")
        if (info["auth"].text() == "0") throw XtreamException("Username or password not accepted")
        return AccountInfo(
            status = info["status"].text(),
            expiresMs = info["exp_date"].text()?.toLongOrNull()?.times(1000),
            maxConnections = info["max_connections"].text(),
            activeConnections = info["active_cons"].text(),
        )
    }

    suspend fun fetchLiveCategories(): ByteArray = fetch(apiUrl("get_live_categories"))
    suspend fun fetchLiveStreams(): ByteArray = fetch(apiUrl("get_live_streams"))

    suspend fun shortEpg(streamId: Int, limit: Int = 4): List<Programme> {
        val bytes = fetch(apiUrl("get_short_epg", "stream_id" to "$streamId", "limit" to "$limit"))
        return withContext(Dispatchers.Default) { XtreamParse.epg(bytes.decodeToString()) }
    }

    /** ext is "ts" or "m3u8". */
    fun liveUrl(streamId: Int, ext: String): String =
        account.server.toHttpUrl().newBuilder()
            .addPathSegment("live")
            .addPathSegment(account.username)
            .addPathSegment(account.password)
            .addPathSegment("$streamId.$ext")
            .build().toString()
}

internal fun JsonElement?.text(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> content.takeIf { it.isNotBlank() }
    else -> null
}

/** Lenient parsing: Xtream panels disagree on whether ids are strings or numbers. */
object XtreamParse {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun categories(bytes: ByteArray): List<Category> {
        val arr = json.parseToJsonElement(bytes.decodeToString()) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["category_id"].text() ?: return@mapNotNull null
            Category(id, o["category_name"].text()?.trim() ?: "Unnamed")
        }
    }

    fun channels(bytes: ByteArray): List<Channel> {
        val arr = json.parseToJsonElement(bytes.decodeToString()) as? JsonArray ?: return emptyList()
        return arr.mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            val id = o["stream_id"].text()?.toIntOrNull() ?: return@mapIndexedNotNull null
            Channel(
                streamId = id,
                num = o["num"].text()?.toIntOrNull() ?: (i + 1),
                name = o["name"].text()?.trim() ?: "Channel $id",
                icon = o["stream_icon"].text(),
                categoryId = o["category_id"].text(),
                epgId = o["epg_channel_id"].text(),
            )
        }.distinctBy { it.streamId }
    }

    fun epg(text: String): List<Programme> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return emptyList()
        val list = root["epg_listings"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val start = o["start_timestamp"].text()?.toLongOrNull() ?: return@mapNotNull null
            val end = (o["stop_timestamp"] ?: o["end_timestamp"]).text()?.toLongOrNull() ?: return@mapNotNull null
            Programme(b64(o["title"].text()), b64(o["description"].text()), start * 1000, end * 1000)
        }.sortedBy { it.startMs }
    }

    private fun b64(s: String?): String {
        if (s == null) return ""
        return try {
            String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (e: IllegalArgumentException) {
            s
        }
    }
}
