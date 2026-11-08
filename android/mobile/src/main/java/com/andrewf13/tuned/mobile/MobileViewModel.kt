package com.andrewf13.tuned.mobile

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.andrewf13.tuned.data.AccountParser
import com.andrewf13.tuned.data.Backup
import com.andrewf13.tuned.data.BackupCrypto
import com.andrewf13.tuned.data.BackupStore
import com.andrewf13.tuned.data.Channel
import com.andrewf13.tuned.data.Lineup
import com.andrewf13.tuned.data.LineupView
import com.andrewf13.tuned.data.NetworkDoctor
import com.andrewf13.tuned.data.Prefs
import com.andrewf13.tuned.data.Profile
import com.andrewf13.tuned.data.Repository
import com.andrewf13.tuned.data.SourceType
import com.andrewf13.tuned.data.XtreamException
import com.andrewf13.tuned.data.guide.GuideStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class Page { Welcome, ProfileForm, Main, Player }
enum class Tab { Live, Favourites, Sports, Settings }

/**
 * Tuned for phones and tablets. Uses the same core as the TV app (logins, profiles,
 * guide, backup format, player engine), so a backup from the TV restores here unchanged.
 */
class MobileViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    val repo = Repository(app).also { it.setProfile(prefs.activeProfile) }
    val doctor = NetworkDoctor(repo.http)
    val backups = BackupStore(app)
    private val local = app.getSharedPreferences("tuned_mobile", 0)

    var page by mutableStateOf(if (prefs.activeProfile == null) Page.Welcome else Page.Main); private set
    var tab by mutableStateOf(Tab.Live)
    var profiles by mutableStateOf(prefs.profiles); private set
    var activeId by mutableStateOf(prefs.activeProfileId); private set
    var editing by mutableStateOf<Profile?>(null); private set

    var view by mutableStateOf(LineupView.EMPTY); private set
    private var raw = Lineup(emptyList(), emptyList())
    var favourites by mutableStateOf(prefs.current.favourites); private set
    var recents by mutableStateOf(prefs.current.recents); private set
    var groupKey by mutableStateOf(prefs.current.lastCategory.takeIf { it != "search" } ?: "all")
    var query by mutableStateOf("")
    var loading by mutableStateOf(false); private set
    var status by mutableStateOf<String?>(null)
    var guideVersion by mutableIntStateOf(0); private set
    var guideBusy by mutableStateOf(false); private set

    // settings
    var themeMode by mutableStateOf(local.getString("theme", "system")!!); private set
    var bufferMode by mutableStateOf(prefs.bufferMode); private set
    var boostDb by mutableIntStateOf(prefs.volumeBoostDb); private set
    var streamFormat by mutableStateOf(prefs.streamFormat); private set
    var sortAlpha by mutableStateOf(prefs.sortAlpha); private set
    var guideCountries by mutableStateOf(prefs.guideCountries); private set
    var sports by mutableStateOf(local.getStringSet("sports", setOf("cricket", "afl", "nrl", "rugby", "f1", "motogp", "wrc", "nfl"))!!.toSet()); private set
    var sportsReplays by mutableStateOf(local.getBoolean("sportsReplays", false)); private set

    // playback
    var playlist by mutableStateOf<List<Channel>>(emptyList()); private set
    var playingIndex by mutableIntStateOf(-1); private set
    val playing: Channel? get() = playlist.getOrNull(playingIndex)
    var fullscreen by mutableStateOf(false)

    init { if (prefs.activeProfile != null) load() }

    // ---- Lineup -------------------------------------------------------------------------
    private fun rebuild() {
        val st = prefs.current
        view = LineupView(raw.channels, raw.categories, st.visibleGroups, st.hiddenChannels, sortAlpha, prefs.groupSortAlpha)
        if (groupKey.startsWith("cat:") && view.categories.none { "cat:${it.id}" == groupKey }) groupKey = "all"
    }

    fun load() {
        viewModelScope.launch {
            loading = true
            repo.loadCached()?.let { raw = it; rebuild() }
            try {
                raw = repo.loadFresh(); rebuild(); status = null
                refreshGuide()
            } catch (e: Exception) {
                status = if (raw.channels.isEmpty()) "Couldn't load channels: ${e.message}" else "Showing your saved channel list"
            } finally {
                loading = false
            }
        }
    }

    fun channelsFor(key: String): List<Channel> = when {
        query.trim().length >= 2 -> view.search(query)
        key == "fav" -> view.favourites(favourites)
        key == "all" -> view.all
        key.startsWith("cat:") -> view.group(key.removePrefix("cat:"))
        else -> view.all
    }

    fun labelFor(key: String) = when {
        key == "fav" -> "Favourites"; key == "all" -> "All channels"
        key.startsWith("cat:") -> view.groupName(key.removePrefix("cat:")) ?: "Channels"
        else -> "Channels"
    }

    fun chooseGroup(key: String) { groupKey = key; if (key != "search") prefs.current.lastCategory = key }

    fun toggleFavourite(id: Int) {
        favourites = if (id in favourites) favourites - id else favourites + id
        prefs.current.favourites = favourites
    }

    fun recentChannels(): List<Channel> = recents.mapNotNull { view.byId[it] }.take(10)

    // ---- Playback -----------------------------------------------------------------------
    fun play(list: List<Channel>, c: Channel) {
        playlist = list.ifEmpty { listOf(c) }
        playingIndex = playlist.indexOfFirst { it.streamId == c.streamId }.coerceAtLeast(0)
        prefs.current.lastStreamId = c.streamId
        page = Page.Player
    }

    fun zap(delta: Int) {
        if (playlist.isEmpty()) return
        playingIndex = (playingIndex + delta).mod(playlist.size)
        playing?.let { prefs.current.lastStreamId = it.streamId }
    }

    fun tune(c: Channel) {
        val i = playlist.indexOfFirst { it.streamId == c.streamId }
        if (i >= 0) playingIndex = i else play(listOf(c), c)
        prefs.current.lastStreamId = c.streamId
    }

    fun markWatched(id: Int) {
        if (recents.firstOrNull() == id) return
        recents = (listOf(id) + (recents - id)).take(11)
        prefs.current.recents = recents
    }

    fun closePlayer() { page = Page.Main; fullscreen = false }

    fun streamUrl(c: Channel) = repo.streamUrl(c, streamFormat)

    // ---- Guide & sports -----------------------------------------------------------------
    fun refreshGuide(force: Boolean = false) {
        val store = repo.guide ?: return
        if (guideBusy || raw.channels.isEmpty()) return
        if (!force && System.currentTimeMillis() - store.updatedAt < 12 * 3_600_000L) return
        viewModelScope.launch {
            guideBusy = true
            try {
                val kept = (view.all.map { it.streamId } + favourites).toSet()
                status = repo.refreshGuide(raw.channels, kept, guideCountries, prefs.guideFromProvider)
                guideVersion++
            } catch (e: Exception) {
                status = "Guide update failed: ${e.message}"
            } finally {
                guideBusy = false
            }
        }
    }

    val guide: GuideStore? get() = repo.guide

    fun toggleSport(id: String) {
        sports = if (id in sports) sports - id else sports + id
        local.edit().putStringSet("sports", sports).apply()
    }
    fun toggleReplays() { sportsReplays = !sportsReplays; local.edit().putBoolean("sportsReplays", sportsReplays).apply() }

    // ---- Settings -----------------------------------------------------------------------
    fun chooseTheme(m: String) { themeMode = m; local.edit().putString("theme", m).apply() }
    fun cycleBuffer() {
        val modes = listOf("small", "normal", "large")
        bufferMode = modes[(modes.indexOf(bufferMode) + 1).mod(3)]; prefs.bufferMode = bufferMode
    }
    fun cycleBoost() {
        val steps = listOf(0, 3, 6, 9, 12)
        boostDb = steps[(steps.indexOf(boostDb) + 1).mod(steps.size)]; prefs.volumeBoostDb = boostDb
    }
    fun toggleFormat() { streamFormat = if (streamFormat == "ts") "m3u8" else "ts"; prefs.streamFormat = streamFormat }
    fun toggleSort() { sortAlpha = !sortAlpha; prefs.sortAlpha = sortAlpha; rebuild() }
    fun toggleGuideCountry(code: String) {
        guideCountries = if (code in guideCountries) guideCountries - code else guideCountries + code
        prefs.guideCountries = guideCountries
    }

    fun isGroupShown(id: String) = prefs.current.visibleGroups?.contains(id) ?: true
    fun toggleGroup(id: String) {
        val cur = prefs.current.visibleGroups ?: view.allCategories.map { it.id }.toSet()
        prefs.current.visibleGroups = if (id in cur) cur - id else cur + id
        rebuild()
    }
    fun setAllGroups(shown: Boolean) { prefs.current.visibleGroups = if (shown) null else emptySet(); rebuild() }

    // ---- Profiles -----------------------------------------------------------------------
    fun addProfile() { editing = null; page = Page.ProfileForm }
    fun editProfile(p: Profile) { editing = p; page = Page.ProfileForm }
    fun closeProfileForm() { page = if (prefs.activeProfile == null) Page.Welcome else Page.Main }

    /** Returns an error message, or null on success. */
    suspend fun saveProfile(name: String, type: SourceType, server: String, user: String, pass: String, m3u: String): String? = try {
        val id = editing?.id ?: UUID.randomUUID().toString().take(8)
        val p = when (type) {
            SourceType.XTREAM -> AccountParser.parse(server, user, pass).let { a ->
                Profile(id, name.trim().ifEmpty { a.server.substringAfter("://").substringBefore(':') }, type, a.server, a.username, a.password)
            }
            SourceType.M3U -> {
                if (m3u.isBlank()) throw XtreamException("Enter the playlist link")
                Profile(id, name.trim().ifEmpty { "Playlist" }, type, m3uUrl = m3u.trim())
            }
        }
        repo.verify(p)
        prefs.upsertProfile(p)
        repo.deleteCache(id)
        switchProfile(id)
        null
    } catch (e: Exception) {
        e.message ?: "Couldn't connect"
    }

    fun switchProfile(id: String) {
        prefs.activeProfileId = id
        reload()
    }

    fun deleteProfile(id: String) {
        repo.deleteCache(id)
        prefs.removeProfile(id)
        reload()
    }

    private fun reload() {
        profiles = prefs.profiles; activeId = prefs.activeProfileId; editing = null
        favourites = prefs.current.favourites; recents = prefs.current.recents
        bufferMode = prefs.bufferMode; boostDb = prefs.volumeBoostDb; streamFormat = prefs.streamFormat
        sortAlpha = prefs.sortAlpha; guideCountries = prefs.guideCountries
        groupKey = prefs.current.lastCategory.takeIf { it != "search" } ?: "all"
        playlist = emptyList(); playingIndex = -1
        raw = Lineup(emptyList(), emptyList()); rebuild()
        repo.setProfile(prefs.activeProfile)
        if (prefs.activeProfile == null) { page = Page.Welcome; return }
        page = Page.Main
        load()
    }

    // ---- Backup -------------------------------------------------------------------------
    suspend fun saveBackup(passphrase: String): String {
        val enc = withContext(Dispatchers.Default) { BackupCrypto.encrypt(Backup.export(prefs), passphrase.toCharArray()) }
        return backups.save(backups.newName(), enc)
    }

    suspend fun restoreBackup(file: ByteArray, passphrase: String) {
        val plain = withContext(Dispatchers.Default) { BackupCrypto.decrypt(file, passphrase.toCharArray()) }
        Backup.import(plain, prefs)
        reload()
    }
}
