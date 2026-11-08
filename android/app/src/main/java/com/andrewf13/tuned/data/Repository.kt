package com.andrewf13.tuned.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class Lineup(val categories: List<Category>, val channels: List<Channel>)

/** Loads and caches the channel list for the active profile (Xtream Codes or M3U playlist). */
class Repository(private val context: Context) {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    var profile: Profile? = null
        private set
    /** Set for Xtream profiles only. */
    var client: XtreamClient? = null
        private set

    fun setProfile(p: Profile?) {
        profile = p
        client = if (p?.type == SourceType.XTREAM) XtreamClient(http, p.account) else null
        epgCache.clear()
    }

    fun clientFor(a: Account) = XtreamClient(http, a)

    private fun file(id: String, part: String) = File(context.filesDir, "lineup_${id}_$part")
    private fun legacy(part: String) = File(context.filesDir, part) // single-login versions

    fun deleteCache(id: String) {
        listOf("cat.json", "streams.json", "playlist.m3u").forEach { file(id, it).delete() }
        if (id == "default") { legacy("live_categories.json").delete(); legacy("live_streams.json").delete() }
    }

    /** Last saved channel list, so the app opens instantly. */
    suspend fun loadCached(): Lineup? = withContext(Dispatchers.IO) {
        val p = profile ?: return@withContext null
        runCatching {
            when (p.type) {
                SourceType.XTREAM -> {
                    var cat = file(p.id, "cat.json"); var str = file(p.id, "streams.json")
                    if (!cat.exists() && p.id == "default") { cat = legacy("live_categories.json"); str = legacy("live_streams.json") }
                    if (!cat.exists() || !str.exists()) null
                    else Lineup(XtreamParse.categories(cat.readBytes()), XtreamParse.channels(str.readBytes()))
                }
                SourceType.M3U -> file(p.id, "playlist.m3u").takeIf { it.exists() }?.let { M3uParse.parse(it.readText()) }
            }
        }.getOrNull()?.takeIf { it.channels.isNotEmpty() }
    }

    suspend fun loadFresh(): Lineup {
        val p = profile ?: throw XtreamException("No provider set up")
        return when (p.type) {
            SourceType.XTREAM -> {
                val c = client!!
                val catBytes = c.fetchLiveCategories()
                val streamBytes = c.fetchLiveStreams()
                val lineup = withContext(Dispatchers.Default) {
                    Lineup(XtreamParse.categories(catBytes), XtreamParse.channels(streamBytes))
                }
                if (lineup.channels.isEmpty()) throw XtreamException("The server returned no live channels")
                withContext(Dispatchers.IO) {
                    file(p.id, "cat.json").writeBytes(catBytes)
                    file(p.id, "streams.json").writeBytes(streamBytes)
                }
                lineup
            }
            SourceType.M3U -> {
                val text = fetchPlaylist(p.m3uUrl)
                val lineup = withContext(Dispatchers.Default) { M3uParse.parse(text) }
                if (lineup.channels.isEmpty()) throw XtreamException("No live channels found in that playlist")
                withContext(Dispatchers.IO) { file(p.id, "playlist.m3u").writeText(text) }
                lineup
            }
        }
    }

    suspend fun fetchPlaylist(url: String): String = withContext(Dispatchers.IO) {
        val u = url.trim().let { if (it.contains("://")) it else "http://$it" }
        http.newCall(Request.Builder().url(u).build()).execute().use { r ->
            if (!r.isSuccessful) throw XtreamException("Playlist server replied ${r.code}")
            r.body?.string() ?: throw XtreamException("Empty playlist")
        }
    }

    /** Checks a profile works before it's saved. Returns how many live channels it has. */
    suspend fun verify(p: Profile): Int = when (p.type) {
        SourceType.XTREAM -> { clientFor(p.account).authenticate(); -1 }
        SourceType.M3U -> {
            val n = withContext(Dispatchers.Default) { M3uParse.parse(fetchPlaylist(p.m3uUrl)).channels.size }
            if (n == 0) throw XtreamException("That link didn't contain any live channels") else n
        }
    }

    fun streamUrl(c: Channel, format: String): String =
        c.url ?: client?.liveUrl(c.streamId, format) ?: ""

    private class CachedEpg(val fetchedAt: Long, val items: List<Programme>)
    private val epgCache = ConcurrentHashMap<Int, CachedEpg>()
    private val epgGate = Semaphore(4)

    /** Now/next for one channel (Xtream only), cached until the current show ends (or 15 min if empty). */
    suspend fun epg(streamId: Int): List<Programme> {
        val now = System.currentTimeMillis()
        epgCache[streamId]?.let { c ->
            val fresh = if (c.items.isEmpty()) now - c.fetchedAt < 15 * 60_000 else c.items.any { it.isOn(now) }
            if (fresh) return c.items
        }
        val c = client ?: return emptyList()
        val items = epgGate.withPermit { runCatching { c.shortEpg(streamId) }.getOrElse { emptyList() } }
        epgCache[streamId] = CachedEpg(now, items)
        return items
    }
}
