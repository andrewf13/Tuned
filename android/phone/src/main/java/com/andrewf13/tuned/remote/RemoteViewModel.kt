package com.andrewf13.tuned.remote

import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Page { Find, Pair, Channels }

class RemoteViewModel(app: Application) : AndroidViewModel(app) {
    private val store = RemoteStore(app)
    val finder = TvFinder(app)

    var page by mutableStateOf(if (store.tv == null) Page.Find else Page.Channels)
        private set
    var client: TvClient? = store.tv?.let { TvClient(it.host, it.port, it.token) }
        private set
    var tvName by mutableStateOf(store.tv?.name ?: "")
        private set
    var pairingWith by mutableStateOf<FoundTv?>(null)
        private set

    var lineup by mutableStateOf(Lineup(emptyList(), emptyList()))
        private set
    val guide = mutableStateMapOf<Int, List<GuideItem>>()
    var state by mutableStateOf<TvState?>(null)
        private set
    var filter by mutableStateOf(store.lastFilter)
        private set
    var themeMode by mutableStateOf(store.themeMode)
        private set

    fun chooseTheme(mode: String) { themeMode = mode; store.themeMode = mode }

    /** Keep the home-screen widget in step with what the app last saw. */
    private fun refreshWidget() {
        store.widgetChannels = lineup.channels.filter { it.fav }.map { it.id to it.name }
        store.nowPlaying = state?.playing?.name
        ChannelWidget.updateAll(getApplication())
    }
    var query by mutableStateOf("")
    var loading by mutableStateOf(false)
        private set
    var problem by mutableStateOf<String?>(null)
    /** One-off message for a snackbar. */
    var toast by mutableStateOf<String?>(null)

    /** The name the TV shows when this phone asks to pair. */
    val deviceName: String = runCatching { Settings.Global.getString(app.contentResolver, "device_name") }.getOrNull()
        ?: Build.MODEL ?: "Phone"

    /** Sports guide, from the TV's guide. */
    val sports by lazy { com.andrewf13.tuned.remote.sports.SportsController(store, viewModelScope, { client }, ::play) }
    /** "channels" or "sports" */
    var tab by mutableStateOf("channels")

    init { if (client != null) connect() }

    // ---- Finding and pairing -----------------------------------------------------------------
    fun startPairing(tv: FoundTv) {
        pairingWith = tv
        problem = null
        val c = TvClient(tv.host, tv.port)
        viewModelScope.launch {
            try {
                val (name, _) = c.hello()
                pairingWith = tv.copy(name = name)
                c.pairStart(deviceName)
                page = Page.Pair
            } catch (e: Exception) {
                problem = "Couldn't reach ${tv.name}. Is Tuned open on the TV, on the same Wi-Fi?"
            }
        }
    }

    /** Manual entry, e.g. "192.168.1.20:8642". */
    fun startPairingAt(address: String) {
        val a = address.trim()
        val host = a.substringBeforeLast(':').ifEmpty { a }
        val port = a.substringAfterLast(':', "8642").toIntOrNull() ?: 8642
        if (host.isBlank()) { problem = "Enter the address shown in Tuned → Settings → Phone remote"; return }
        startPairing(FoundTv(host, host, port))
    }

    fun finishPairing(code: String, onWrong: () -> Unit) {
        val tv = pairingWith ?: return
        val c = TvClient(tv.host, tv.port)
        viewModelScope.launch {
            try {
                val token = c.pairFinish(deviceName, code)
                store.tv = SavedTv(tv.name, tv.host, tv.port, token)
                client = c
                tvName = tv.name
                page = Page.Channels
                connect()
            } catch (e: TvException) {
                if (e.status == 403) { problem = "That code isn't right. Check the TV and try again."; onWrong() }
                else { problem = e.message; page = Page.Find }
            } catch (e: Exception) {
                problem = "Lost touch with the TV. Try again."
            }
        }
    }

    fun cancelPairing() { pairingWith = null; page = Page.Find }

    fun forgetTv() {
        val c = client
        viewModelScope.launch { c?.unpair() }
        store.tv = null
        client = null
        lineup = Lineup(emptyList(), emptyList()); guide.clear(); state = null
        ChannelWidget.updateAll(getApplication())
        page = Page.Find
    }

    // ---- Channels ------------------------------------------------------------------------
    private var poll: Job? = null

    fun connect() {
        val c = client ?: return
        viewModelScope.launch {
            loading = true
            try {
                lineup = c.lineup()
                state = c.state()
                problem = null
                refreshWidget()
                if (filter == "fav" && lineup.channels.none { it.fav }) filter = "all"
                if (filter.startsWith("g:") && lineup.groups.none { "g:${it.id}" == filter }) filter = "all"
            } catch (e: TvException) {
                if (e.status == 401) { problem = "The TV forgot this phone. Pair again."; forgetTv() }
                else problem = e.message
            } catch (e: Exception) {
                problem = "Can't reach $tvName. Is Tuned open on the TV, on the same Wi-Fi?"
            } finally {
                loading = false
            }
        }
        poll?.cancel()
        poll = viewModelScope.launch {
            while (true) {
                delay(10_000)
                runCatching { state = c.state(); problem = null }
            }
        }
    }

    fun chooseFilter(f: String) { filter = f; store.lastFilter = f }

    fun visible(): List<TvChannel> {
        val q = query.trim()
        val base = when {
            q.length >= 2 -> lineup.channels
            filter == "fav" -> lineup.channels.filter { it.fav }
            filter.startsWith("g:") -> lineup.channels.filter { "g:${it.group}" == filter }
            else -> lineup.channels
        }
        val list = if (q.length >= 2) base.filter { it.name.contains(q, ignoreCase = true) } else base
        return list.distinctBy { it.id }
    }

    /** Fetch guide data for these channels if we don't have a current programme for them. */
    fun needGuide(ids: List<Int>) {
        val c = client ?: return
        val now = System.currentTimeMillis()
        val missing = ids.filter { id -> guide[id]?.let { items -> items.isNotEmpty() && items.none { it.isOn(now) } } ?: true }
            .filter { it !in requested }
        if (missing.isEmpty()) return
        requested += missing
        viewModelScope.launch {
            runCatching { c.guide(missing) }.onSuccess { guide.putAll(it) }
            requested -= missing.toSet()
        }
    }
    private val requested = HashSet<Int>()

    fun play(ch: TvChannel) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                state = c.play(ch.id)
                toast = "Now on ${state?.name ?: "TV"}: ${ch.name}"
                refreshWidget()
            } catch (e: TvException) {
                toast = e.message
            } catch (e: Exception) {
                toast = "Couldn't reach the TV"
            }
        }
    }
}
