package com.andrewf13.tuned.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class Lineup(val categories: List<Category>, val channels: List<Channel>)

class Repository(context: Context, private val prefs: Prefs) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    var client: XtreamClient? = prefs.account?.let { XtreamClient(http, it) }
        private set

    private val catFile = File(context.filesDir, "live_categories.json")
    private val streamsFile = File(context.filesDir, "live_streams.json")

    fun setAccount(a: Account?) {
        client = a?.let { XtreamClient(http, it) }
        prefs.account = a
        if (a == null) {
            catFile.delete(); streamsFile.delete(); epgCache.clear()
        }
    }

    fun clientFor(a: Account) = XtreamClient(http, a)

    /** Last saved channel list, so the app opens instantly. */
    suspend fun loadCached(): Lineup? = withContext(Dispatchers.IO) {
        if (!catFile.exists() || !streamsFile.exists()) return@withContext null
        runCatching {
            Lineup(XtreamParse.categories(catFile.readBytes()), XtreamParse.channels(streamsFile.readBytes()))
        }.getOrNull()?.takeIf { it.channels.isNotEmpty() }
    }

    suspend fun loadFresh(): Lineup {
        val c = client ?: throw XtreamException("Not signed in")
        val catBytes = c.fetchLiveCategories()
        val streamBytes = c.fetchLiveStreams()
        val lineup = withContext(Dispatchers.Default) {
            Lineup(XtreamParse.categories(catBytes), XtreamParse.channels(streamBytes))
        }
        if (lineup.channels.isEmpty()) throw XtreamException("The server returned no live channels")
        withContext(Dispatchers.IO) {
            catFile.writeBytes(catBytes)
            streamsFile.writeBytes(streamBytes)
        }
        return lineup
    }

    private class CachedEpg(val fetchedAt: Long, val items: List<Programme>)
    private val epgCache = ConcurrentHashMap<Int, CachedEpg>()
    private val epgGate = Semaphore(4)

    /** Now/next for one channel, cached until the current show ends (or 15 min if empty). */
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
