package com.andrewf13.tuned.remote.sports

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.andrewf13.tuned.remote.RemoteStore
import com.andrewf13.tuned.remote.TvChannel
import com.andrewf13.tuned.remote.TvClient
import com.andrewf13.tuned.remote.TvException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The sports guide: matches found in the TV's guide for the sports you follow.
 * The last result is kept on the phone, so it still shows (read-only) while the TV is off.
 */
class SportsController(
    private val store: RemoteStore,
    private val scope: CoroutineScope,
    private val tv: () -> TvClient?,
    private val play: (TvChannel) -> Unit,
) {
    var sports by mutableStateOf(store.sports); private set
    var keywords by mutableStateOf(store.sportsKeywords); private set
    var replays by mutableStateOf(store.sportsReplays); private set
    var catalog by mutableStateOf(store.sportsCatalog?.let { TvClient("", 0).parseCatalog(it) }?.takeIf { it.isNotEmpty() } ?: Sports.fallback); private set
    var events by mutableStateOf<List<SportEvent>>(emptyList()); private set
    /** When the TV last updated its guide (ms). */
    var guideUpdated by mutableStateOf(0L); private set
    var loading by mutableStateOf(false); private set
    var problem by mutableStateOf<String?>(null); private set
    /** True when showing the copy saved on the phone because the TV can't be reached. */
    var offline by mutableStateOf(false); private set
    var filter by mutableStateOf("all")

    init {
        store.sportsCache?.let { cached -> TvClient("", 0).parseSports(cached).let { (u, e) -> guideUpdated = u; events = e } }
    }

    fun save(newSports: Set<String>, newKeywords: List<String>, newReplays: Boolean) {
        store.sports = newSports; sports = newSports
        store.sportsKeywords = newKeywords; keywords = newKeywords
        store.sportsReplays = newReplays; replays = newReplays
        load()
    }

    fun load() {
        val c = tv() ?: return
        if (loading) return
        scope.launch {
            loading = true
            problem = null
            try {
                runCatching { c.sportsCatalogJson() }.getOrNull()?.let { j ->
                    c.parseCatalog(j).takeIf { it.isNotEmpty() }?.let { catalog = it; store.sportsCatalog = j }
                }
                val json = c.sportsJson(sports, keywords, replays)
                val (u, e) = c.parseSports(json)
                events = e; guideUpdated = u; offline = false
                store.sportsCache = json
            } catch (e: TvException) {
                problem = when (e.status) {
                    409 -> "The TV is still downloading its guide. Try again in a minute."
                    404 -> "Update Tuned on the TV to 1.0 or later for the sports guide."
                    else -> e.message
                }
            } catch (e: Exception) {
                offline = events.isNotEmpty()
                problem = if (offline) null else "Can't reach the TV. Open Tuned on the TV to load the sports guide."
            } finally {
                loading = false
            }
        }
    }

    fun watch(ch: TvChannel) = play(ch)

    fun label(id: String?) = catalog.firstOrNull { it.id == id }

    fun visible(now: Long): List<SportEvent> = events.filter { e ->
        e.endMs > now && when (filter) {
            "all" -> true
            "followed" -> e.followed
            else -> e.sport == filter
        }
    }
}
