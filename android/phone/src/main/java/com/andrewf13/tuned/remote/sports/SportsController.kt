package com.andrewf13.tuned.remote.sports

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.andrewf13.tuned.remote.RemoteStore
import com.andrewf13.tuned.remote.TvChannel
import com.andrewf13.tuned.remote.TvClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/** What a broadcaster resolves to on the TV. */
data class Resolved(val channel: TvChannel, val groupName: String, val picked: Boolean)

/**
 * The sports guide: listings from TheSportsDB for the sports you follow, matched to channels
 * on your TV. Tapping a match changes channel through [play].
 */
class SportsController(
    private val store: RemoteStore,
    private val scope: CoroutineScope,
    private val tv: () -> TvClient?,
    private val play: (TvChannel) -> Unit,
    private val sportsDbBase: String = "https://www.thesportsdb.com",
) {
    var key by mutableStateOf(store.sportsKey); private set
    var sports by mutableStateOf(store.sports); private set
    var countries by mutableStateOf(store.sportsCountries); private set
    var keywords by mutableStateOf(store.sportsKeywords); private set
    var events by mutableStateOf<List<SportEvent>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var problem by mutableStateOf<String?>(null); private set
    var lastLoaded = 0L; private set
    /** "All", "followed", or a sport's db name. */
    var filter by mutableStateOf("all")
    /** Broadcaster key → candidates from the TV. */
    val matches = mutableStateMapOf<String, List<TvClient.Candidate>>()
    private var picks by mutableStateOf(store.channelPicks)

    val configured get() = key.isNotBlank() && sports.isNotEmpty()
    val usingFreeKey get() = key == SportsDb.FREE_KEY

    fun save(newKey: String, newSports: Set<String>, newCountries: Set<String>, newKeywords: List<String>) {
        store.sportsKey = newKey; key = newKey.trim()
        store.sports = newSports; sports = newSports
        store.sportsCountries = newCountries; countries = newCountries
        store.sportsKeywords = newKeywords; keywords = newKeywords
        load(force = true)
    }

    /** Fetch listings (cached for an hour, as TheSportsDB asks) and match them to TV channels. */
    fun load(force: Boolean = false) {
        if (!configured || loading) return
        if (!force && System.currentTimeMillis() - lastLoaded < 60 * 60_000 && events.isNotEmpty()) { matchAll(); return }
        scope.launch {
            loading = true
            problem = null
            try {
                val db = SportsDb(key, sportsDbBase)
                val now = System.currentTimeMillis()
                val days = SportsDb.utcDays(now - 86_400_000L, 4)
                val rows = sports.map { s -> async { runCatching { db.listings(s, days) } } }.awaitAll()
                rows.firstNotNullOfOrNull { it.exceptionOrNull() as? SportsDbException }?.let { if (rows.all { r -> r.isFailure }) throw it }
                events = EventBuilder.build(rows.flatMap { it.getOrDefault(emptyList()) }, countries, keywords, now)
                lastLoaded = now
                if (events.isEmpty()) problem = if (db.isFreeKey) "The free test key only returns one listing per sport per day. Add a premium key for the full guide." else "No upcoming events on your chosen channels' countries."
                matchAll()
            } catch (e: SportsDbException) {
                problem = e.message
            } catch (e: Exception) {
                problem = "Couldn't load the sports guide. Check this phone's internet."
            } finally {
                loading = false
            }
        }
    }

    private fun matchAll() {
        val c = tv() ?: return
        val wanted = events.flatMap { it.broadcasts }.distinctBy { it.key }.filter { it.key !in matches }
        if (wanted.isEmpty()) return
        scope.launch {
            try {
                wanted.chunked(150).forEach { part -> matches.putAll(c.match(part.map { it.channel to it.country })) }
            } catch (e: com.andrewf13.tuned.remote.TvException) {
                if (e.status == 404) problem = "Update Tuned on the TV to 0.9 or later to match these to your channels."
            } catch (_: Exception) {
                // TV unreachable: channels show as unmatched until the next refresh
            }
        }
    }

    /** The TV channel for a broadcaster: your own pick first, then a confident automatic match. */
    fun resolve(b: Broadcast): Resolved? {
        picks[b.key]?.let { id ->
            val c = matches[b.key]?.firstOrNull { it.channel.id == id }
            return Resolved(c?.channel ?: TvChannel(id, 0, b.channel, null, null, false), c?.groupName.orEmpty(), picked = true)
        }
        val best = matches[b.key]?.firstOrNull { it.score >= 80 } ?: return null
        return Resolved(best.channel, best.groupName, picked = false)
    }

    fun candidates(b: Broadcast): List<TvClient.Candidate> = matches[b.key].orEmpty()

    /** Remember your own channel for a broadcaster (null = go back to automatic). */
    fun pick(b: Broadcast, channel: TvChannel?) {
        picks = if (channel == null) picks - b.key else picks + (b.key to channel.id)
        store.channelPicks = picks
        if (channel != null && matches[b.key]?.none { it.channel.id == channel.id } != false) {
            matches[b.key] = listOf(TvClient.Candidate(channel, "", 100)) + matches[b.key].orEmpty()
        }
    }

    fun watch(b: Broadcast) { resolve(b)?.let { play(it.channel) } }

    fun visible(now: Long): List<SportEvent> = events.filter { e ->
        e.endMs > now && when (filter) {
            "all" -> true
            "followed" -> e.followed
            else -> e.sport.equals(filter, ignoreCase = true)
        }
    }
}
