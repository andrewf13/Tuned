package com.andrewf13.tuned

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.andrewf13.tuned.data.AccountInfo
import com.andrewf13.tuned.data.AccountParser
import com.andrewf13.tuned.data.Category
import com.andrewf13.tuned.data.Channel
import com.andrewf13.tuned.data.Lineup
import com.andrewf13.tuned.data.Prefs
import com.andrewf13.tuned.data.Repository
import kotlinx.coroutines.launch

/** Login is the add/edit-profile form (also the first-run screen). */
enum class Screen { Login, Loading, Home, Player, Settings, Groups, GroupChannels, Backup, Profiles, Guide }

data class RailItem(val key: String, val label: String, val count: Int?)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    val repo = Repository(app).also { it.setProfile(prefs.activeProfile) }
    val doctor = com.andrewf13.tuned.data.NetworkDoctor(repo.http)

    var screen by mutableStateOf(if (prefs.activeProfile == null) Screen.Login else Screen.Loading)
        private set
    var profiles by mutableStateOf(prefs.profiles)
        private set
    var activeProfileId by mutableStateOf(prefs.activeProfileId)
        private set
    /** Profile open in the form, or null when adding a new one. */
    var editingProfile by mutableStateOf<com.andrewf13.tuned.data.Profile?>(null)
        private set

    // ---- Lineup ---------------------------------------------------------------------------
    /** Every live channel from the provider (search covers all of them). */
    var channels by mutableStateOf<List<Channel>>(emptyList())
        private set
    /** Provider groups that have channels, in provider order. */
    var allCategories by mutableStateOf<List<Category>>(emptyList())
        private set
    /** Groups shown in the rail (after the user's group choice). */
    var categories by mutableStateOf<List<Category>>(emptyList())
        private set
    /** All channels per group. */
    var groupCounts: Map<String, Int> = emptyMap()
        private set
    /** Ticked channels per group. */
    var groupShownCounts: Map<String, Int> = emptyMap()
        private set
    /** Bumped whenever lists are rebuilt, so the UI can key its caches on it. */
    var listVersion by mutableIntStateOf(0)
        private set

    private var rawCategories: List<Category> = emptyList()
    private var byCategory: Map<String, List<Channel>> = emptyMap()
    private var allVisible: List<Channel> = emptyList()
    private var byId: Map<Int, Channel> = emptyMap()

    var favourites by mutableStateOf(prefs.current.favourites)
        private set
    var hiddenChannels by mutableStateOf(prefs.current.hiddenChannels)
        private set
    /** The group open in the channel picker. */
    var editingGroupId by mutableStateOf<String?>(null)
        private set
    private var allByCategory: Map<String?, List<Channel>> = emptyMap()
    var groupFilter by mutableStateOf(prefs.current.visibleGroups)
        private set
    var sortAlpha by mutableStateOf(prefs.sortAlpha)
        private set
    var groupSortAlpha by mutableStateOf(prefs.groupSortAlpha)
        private set

    /** Set when the rail should open (and take focus) as Home appears, e.g. second Left in full screen. */
    var railRequested by mutableStateOf(false)
    var recents by mutableStateOf(prefs.current.recents)
        private set

    var selectedKey by mutableStateOf(prefs.current.lastCategory.takeIf { it != "search" } ?: "all")
        private set
    var searchQuery by mutableStateOf("")
    var status by mutableStateOf<String?>(null)
    var refreshing by mutableStateOf(false)
        private set

    /** Row to focus when the channel list is shown (e.g. after leaving the player). */
    var focusStreamId by mutableStateOf<Int?>(prefs.current.lastStreamId.takeIf { it > 0 })

    // ---- Settings -------------------------------------------------------------------------
    var afrEnabled by mutableStateOf(prefs.afrEnabled)
        private set
    var streamFormat by mutableStateOf(prefs.streamFormat)
        private set
    var resumeOnStart by mutableStateOf(prefs.resumeOnStart)
        private set
    var volumeBoostDb by mutableIntStateOf(prefs.volumeBoostDb)
        private set
    var bufferMode by mutableStateOf(prefs.bufferMode)
        private set

    // ---- Playback -------------------------------------------------------------------------
    /** The list Up/Down zaps through and Left shows: the group the channel was started from. */
    var playlist by mutableStateOf<List<Channel>>(emptyList())
        private set
    /** Rail key the playlist came from ("cat:12", "fav", "search", …). */
    var playlistKey by mutableStateOf("all")
        private set
    val playlistLabel: String get() = labelFor(playlistKey)
    var playingIndex by mutableIntStateOf(-1)
        private set
    val playing: Channel? get() = playlist.getOrNull(playingIndex)

    init {
        if (prefs.activeProfile != null) start(resume = true)
    }

    private fun apply(l: Lineup) {
        rawCategories = l.categories
        channels = l.channels
        rebuild()
    }

    private fun sorted(list: List<Channel>): List<Channel> =
        if (sortAlpha) list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) else list

    /** Recompute everything derived from the lineup, group choice and sort order. */
    private fun rebuild() {
        val grouped = channels.groupBy { it.categoryId }
        allByCategory = grouped
        byId = channels.associateBy { it.streamId }
        val hidden = hiddenChannels
        val shownGrouped = if (hidden.isEmpty()) grouped else grouped.mapValues { (_, l) -> l.filter { it.streamId !in hidden } }
        val withChannels = rawCategories.filter { grouped[it.id]?.isNotEmpty() == true }
        allCategories = if (groupSortAlpha) withChannels.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.trim() }) else withChannels
        groupCounts = allCategories.associate { it.id to grouped[it.id]!!.size }
        groupShownCounts = allCategories.associate { it.id to shownGrouped[it.id].orEmpty().size }
        val f = groupFilter
        // A ticked group whose channels are all unticked drops out of the rail.
        categories = allCategories.filter { (f == null || it.id in f) && (groupShownCounts[it.id] ?: 0) > 0 }
        byCategory = categories.associate { it.id to sorted(shownGrouped[it.id].orEmpty()) }
        val shownIds = categories.map { it.id }.toSet()
        allVisible = sorted(channels.filter { it.categoryId in shownIds && it.streamId !in hidden })
        if (railItems().none { it.key == selectedKey }) selectedKey = "all"
        // Keep the zapping list in step with the new order.
        playing?.let { p ->
            val same = channelsFor(playlistKey).takeIf { l -> l.any { it.streamId == p.streamId } }
            if (same != null) setPlaylist(same, playlistKey, p) else listFor(p)?.let { (list, key) -> setPlaylist(list, key, p) }
        }
        listVersion++
    }

    private fun start(resume: Boolean) {
        viewModelScope.launch {
            val cached = repo.loadCached()
            if (cached != null) {
                apply(cached)
                enterHome(resume)
            }
            refreshing = true
            try {
                apply(repo.loadFresh())
                status = null
                if (cached == null) enterHome(resume)
                refreshGuide()
            } catch (e: Exception) {
                status = if (cached == null) "Couldn't load channels: ${e.message}" else "Showing your saved channel list (${e.message})"
                if (cached == null) screen = Screen.Home
            } finally {
                refreshing = false
            }
        }
    }

    private fun enterHome(resume: Boolean) {
        screen = Screen.Home
        if (resume && prefs.resumeOnStart) {
            val last = byId[prefs.current.lastStreamId] ?: return
            val list = channelsFor(selectedKey).takeIf { l -> l.any { it.streamId == last.streamId } }
            if (list != null) play(list, selectedKey, last) else tune(last).also { screen = Screen.Player }
        }
    }

    // ---- Lists ----------------------------------------------------------------------------
    fun railItems(): List<RailItem> = buildList {
        add(RailItem("search", "Search", null))
        add(RailItem("fav", "Favourites", favourites.count { it in byId }))
        add(RailItem("all", "All channels", allVisible.size))
        categories.forEach { add(RailItem("cat:${it.id}", it.name, byCategory[it.id]?.size)) }
    }

    fun labelFor(key: String): String = when {
        key == "fav" -> "Favourites"
        key == "all" -> "All channels"
        key == "search" -> "Search"
        key.startsWith("cat:") -> allCategories.firstOrNull { it.id == key.removePrefix("cat:") }?.name ?: ""
        else -> ""
    }

    fun channelsFor(key: String): List<Channel> = when {
        key == "fav" -> sorted(channels.filter { it.streamId in favourites })
        key == "all" -> allVisible
        key == "search" -> {
            val q = searchQuery.trim()
            if (q.length < 2) emptyList() else sorted(channels.filter { it.name.contains(q, ignoreCase = true) })
        }
        key.startsWith("cat:") -> byCategory[key.removePrefix("cat:")].orEmpty()
        else -> emptyList()
    }

    /** The channel's own group (even if hidden), for zapping when it was reached some other way. */
    private fun listFor(c: Channel): Pair<List<Channel>, String>? {
        val id = c.categoryId ?: return null
        val list = byCategory[id] ?: sorted(channels.filter { it.categoryId == id })
        return if (list.any { it.streamId == c.streamId }) list to "cat:$id" else null
    }

    fun selectCategory(key: String) {
        if (key == selectedKey) return
        selectedKey = key
        // Search is never remembered: reopening the app should land on real channels.
        if (key != "search") prefs.current.lastCategory = key
    }

    // ---- Playback -------------------------------------------------------------------------
    private fun setPlaylist(list: List<Channel>, key: String, c: Channel) {
        playlist = list
        playlistKey = key
        playingIndex = list.indexOfFirst { it.streamId == c.streamId }.coerceAtLeast(0)
        prefs.current.lastStreamId = c.streamId
    }

    /** Start (or keep) a channel playing behind the list. */
    fun preview(list: List<Channel>, key: String, c: Channel) = setPlaylist(list, key, c)

    /** Start a channel full screen. */
    fun play(list: List<Channel>, key: String, c: Channel) {
        setPlaylist(list, key, c)
        screen = Screen.Player
    }

    /** Switch channel without changing screen; stays in the current list if it's there. */
    fun tune(c: Channel) {
        val idx = playlist.indexOfFirst { it.streamId == c.streamId }
        if (idx >= 0) {
            playingIndex = idx
            prefs.current.lastStreamId = c.streamId
        } else {
            val (list, key) = listFor(c) ?: (listOf(c) to "all")
            setPlaylist(list, key, c)
        }
    }

    fun zap(delta: Int) {
        if (playlist.isEmpty()) return
        playingIndex = (playingIndex + delta).mod(playlist.size)
        playing?.let { prefs.current.lastStreamId = it.streamId }
    }

    /** Called once a channel has been watched for a few seconds, so zapping doesn't fill the list. */
    fun markWatched(id: Int) {
        if (recents.firstOrNull() == id) return
        recents = (listOf(id) + (recents - id)).take(11)
        prefs.current.recents = recents
    }

    fun recentChannels(): List<Channel> =
        recents.filter { it != playing?.streamId }.mapNotNull { byId[it] }.take(10)

    /** The last channel you settled on before this one. */
    fun previousChannel(): Channel? =
        recents.firstOrNull { it != playing?.streamId }?.let { byId[it] }

    fun goFullscreen() { if (playing != null) screen = Screen.Player }

    fun closePlayer() {
        focusStreamId = playing?.streamId
        screen = Screen.Home
    }

    /** Second Left in full screen: channel list with the groups open, on this channel's group. */
    fun openGroupsRail() {
        val key = playlistKey.takeIf { k -> k == "fav" || k == "all" || k == "search" || categories.any { "cat:${it.id}" == k } } ?: "all"
        selectCategory(key)
        railRequested = true
        closePlayer()
        focusStreamId = null // the rail takes focus, not the channel row
    }

    fun streamUrl(c: Channel): String = repo.streamUrl(c, streamFormat)

    fun toggleFavourite(id: Int) {
        favourites = if (id in favourites) favourites - id else favourites + id
        prefs.current.favourites = favourites
    }

    // ---- Groups ---------------------------------------------------------------------------
    fun isGroupShown(id: String) = groupFilter?.contains(id) ?: true

    fun toggleGroup(id: String) {
        val cur = groupFilter ?: allCategories.map { it.id }.toSet()
        applyGroupFilter(if (id in cur) cur - id else cur + id)
    }

    // ---- Channels inside a group ------------------------------------------------------------
    fun openGroupChannels(id: String) { editingGroupId = id; screen = Screen.GroupChannels }
    fun closeGroupChannels() { screen = Screen.Groups }

    /** Every channel in the group (ticked or not), in the current channel order. */
    fun channelsInGroup(id: String): List<Channel> = sorted(allByCategory[id].orEmpty())

    fun isChannelShown(id: Int) = id !in hiddenChannels

    private fun setHidden(h: Set<Int>, groupId: String) {
        hiddenChannels = h
        prefs.current.hiddenChannels = h
        // Ticking a channel in an unticked group makes the group show up too.
        if (!isGroupShown(groupId) && channelsInGroup(groupId).any { it.streamId !in h }) {
            val cur = groupFilter ?: allCategories.map { it.id }.toSet()
            groupFilter = cur + groupId
            prefs.current.visibleGroups = groupFilter
        }
        rebuild()
    }

    fun toggleChannel(id: Int, groupId: String) =
        setHidden(if (id in hiddenChannels) hiddenChannels - id else hiddenChannels + id, groupId)

    fun tickAllChannels(groupId: String) =
        setHidden(hiddenChannels - channelsInGroup(groupId).map { it.streamId }.toSet(), groupId)

    fun untickAllChannels(groupId: String) =
        setHidden(hiddenChannels + channelsInGroup(groupId).map { it.streamId }, groupId)

    fun showAllGroups() = applyGroupFilter(null)
    fun hideAllGroups() = applyGroupFilter(emptySet())

    private fun applyGroupFilter(f: Set<String>?) {
        groupFilter = f
        prefs.current.visibleGroups = f
        rebuild()
    }

    // ---- Screens & settings ---------------------------------------------------------------
    fun openSettings() { screen = Screen.Settings }
    fun openGroups() { screen = Screen.Groups }
    fun backToHome() { screen = Screen.Home }

    fun toggleAfr() { afrEnabled = !afrEnabled; prefs.afrEnabled = afrEnabled }
    fun toggleFormat() { streamFormat = if (streamFormat == "ts") "m3u8" else "ts"; prefs.streamFormat = streamFormat }
    fun toggleResume() { resumeOnStart = !resumeOnStart; prefs.resumeOnStart = resumeOnStart }
    fun toggleGroupSort() { groupSortAlpha = !groupSortAlpha; prefs.groupSortAlpha = groupSortAlpha; rebuild() }
    fun toggleSort() { sortAlpha = !sortAlpha; prefs.sortAlpha = sortAlpha; rebuild() }

    fun cycleTheme() {
        val all = com.andrewf13.tuned.ui.Themes.all
        val next = all[(all.indexOfFirst { it.id == prefs.themeId } + 1).mod(all.size)]
        prefs.themeId = next.id
        com.andrewf13.tuned.ui.Palette.theme = next
    }

    fun cycleBoost() {
        val steps = listOf(0, 3, 6, 9, 12)
        volumeBoostDb = steps[(steps.indexOf(volumeBoostDb) + 1).mod(steps.size)]
        prefs.volumeBoostDb = volumeBoostDb
    }

    fun cycleBuffer() {
        val modes = listOf("small", "normal", "large")
        bufferMode = modes[(modes.indexOf(bufferMode) + 1).mod(modes.size)]
        prefs.bufferMode = bufferMode
    }

    fun refresh() {
        if (refreshing) return
        viewModelScope.launch {
            refreshing = true
            try { apply(repo.loadFresh()); status = null } catch (e: Exception) { status = "Refresh failed: ${e.message}" }
            finally { refreshing = false }
        }
    }

    suspend fun accountInfo(): AccountInfo? = runCatching { repo.client?.authenticate() }.getOrNull()

    // ---- TV guide -------------------------------------------------------------------------
    var guideCountries by mutableStateOf(prefs.guideCountries)
        private set
    var guideFromProvider by mutableStateOf(prefs.guideFromProvider)
        private set
    var guideBusy by mutableStateOf(false)
        private set
    var guideStatus by mutableStateOf<String?>(null)
        private set

    fun toggleGuideCountry(code: String) {
        guideCountries = if (code in guideCountries) guideCountries - code else guideCountries + code
        prefs.guideCountries = guideCountries
    }
    fun toggleGuideFromProvider() { guideFromProvider = !guideFromProvider; prefs.guideFromProvider = guideFromProvider }
    fun openGuideSettings() { screen = Screen.Guide }

    /** Rebuild the guide if it's older than 12 hours (or now, if [force]). Runs in the background. */
    fun refreshGuide(force: Boolean = false) {
        if (guideBusy || channels.isEmpty()) return
        val store = repo.guide ?: return
        if (!force && System.currentTimeMillis() - store.updatedAt < 12 * 3_600_000L) return
        viewModelScope.launch {
            guideBusy = true
            guideStatus = "Updating the TV guide…"
            guideStatus = try {
                repo.refreshGuide(channels, remoteChannels().map { it.streamId }.toSet(), guideCountries, guideFromProvider)
            } catch (e: Exception) {
                "Guide update failed: ${e.message}"
            }
            guideBusy = false
            listVersion++ // rows re-read what's on
        }
    }

    // ---- Phone remote ---------------------------------------------------------------------
    /** Device name and code to show while a phone is pairing. */
    var pairing by mutableStateOf<Pair<String, String>?>(null)
    var remoteAddress by mutableStateOf<String?>(null)
    var pairedPhones by mutableIntStateOf(0)

    /** Change channel from the phone: tune and show it full screen. */
    fun tuneFromRemote(id: Int): Boolean {
        val c = channels.firstOrNull { it.streamId == id } ?: return false
        if (screen == Screen.Login || screen == Screen.Loading) return false
        tune(c)
        screen = Screen.Player
        return true
    }

    fun groupNameFor(id: String?): String? = allCategories.firstOrNull { it.id == id }?.name

    /** What the phone sees: the TV's own list (ticked groups and channels), plus favourites. */
    fun remoteChannels(): List<Channel> {
        val favs = channelsFor("fav")
        val seen = HashSet<Int>()
        return (favs + allVisible).filter { seen.add(it.streamId) }
    }

    // ---- Profiles -------------------------------------------------------------------------
    fun openProfiles() { screen = Screen.Profiles }
    fun addProfile() { editingProfile = null; screen = Screen.Login }
    fun editProfile(p: com.andrewf13.tuned.data.Profile) { editingProfile = p; screen = Screen.Login }
    fun closeProfileForm() { screen = if (prefs.activeProfile == null) Screen.Login else Screen.Profiles }

    /**
     * Checks and saves a profile from the form, then switches to it.
     * Xtream: [server] may also be a full get.php link. M3U: [m3uUrl] is the playlist link.
     * Returns an error message, or null on success.
     */
    suspend fun saveProfile(
        name: String, type: com.andrewf13.tuned.data.SourceType,
        server: String, user: String, pass: String, m3uUrl: String,
    ): String? {
        return try {
            val existing = editingProfile
            val id = existing?.id ?: java.util.UUID.randomUUID().toString().take(8)
            val profile = when (type) {
                com.andrewf13.tuned.data.SourceType.XTREAM -> {
                    val a = AccountParser.parse(server, user, pass)
                    com.andrewf13.tuned.data.Profile(id, name.trim().ifEmpty { hostName(a.server) }, type, a.server, a.username, a.password)
                }
                com.andrewf13.tuned.data.SourceType.M3U -> {
                    if (m3uUrl.isBlank()) throw com.andrewf13.tuned.data.XtreamException("Enter the playlist link")
                    com.andrewf13.tuned.data.Profile(id, name.trim().ifEmpty { hostName(m3uUrl) }, type, m3uUrl = m3uUrl.trim())
                }
            }
            repo.verify(profile)
            val sourceChanged = existing == null || existing.type != profile.type || existing.server != profile.server ||
                existing.username != profile.username || existing.m3uUrl != profile.m3uUrl
            prefs.upsertProfile(profile)
            if (sourceChanged) repo.deleteCache(id)
            profiles = prefs.profiles
            if (sourceChanged || activeProfileId != id) switchProfile(id) else { editingProfile = null; screen = Screen.Profiles }
            null
        } catch (e: Exception) {
            e.message ?: "Couldn't connect"
        }
    }

    private fun hostName(url: String) =
        url.substringAfter("://").substringBefore('/').substringBefore(':').ifEmpty { "Provider" }

    /** Make another profile active: its own channels, favourites, groups and recents. */
    fun switchProfile(id: String) {
        prefs.activeProfileId = id
        activeProfileId = id
        editingProfile = null
        reloadFromPrefs()
    }

    fun deleteProfile(id: String) {
        repo.deleteCache(id)
        prefs.removeProfile(id)
        profiles = prefs.profiles
        if (activeProfileId == id || prefs.activeProfile == null) {
            activeProfileId = prefs.activeProfileId
            reloadFromPrefs()
        }
    }

    /** Re-read everything from storage (after a profile switch or a restore) and reload channels. */
    private fun reloadFromPrefs() {
        val st = prefs.current
        favourites = st.favourites
        hiddenChannels = st.hiddenChannels
        groupFilter = st.visibleGroups
        recents = st.recents
        selectedKey = st.lastCategory.takeIf { it != "search" } ?: "all"
        focusStreamId = st.lastStreamId.takeIf { it > 0 }
        sortAlpha = prefs.sortAlpha
        groupSortAlpha = prefs.groupSortAlpha
        afrEnabled = prefs.afrEnabled
        streamFormat = prefs.streamFormat
        resumeOnStart = prefs.resumeOnStart
        volumeBoostDb = prefs.volumeBoostDb
        bufferMode = prefs.bufferMode
        com.andrewf13.tuned.ui.Palette.theme = com.andrewf13.tuned.ui.Themes.byId(prefs.themeId)
        profiles = prefs.profiles
        activeProfileId = prefs.activeProfileId
        playlist = emptyList(); playingIndex = -1
        rawCategories = emptyList(); channels = emptyList(); rebuild()
        repo.setProfile(prefs.activeProfile)
        if (prefs.activeProfile == null) { editingProfile = null; screen = Screen.Login; return }
        screen = Screen.Loading
        start(resume = false)
    }

    // ---- Backup ---------------------------------------------------------------------------
    val backups = com.andrewf13.tuned.data.BackupStore(app)

    /** Encrypts and saves a backup. Returns where it went. */
    suspend fun saveBackup(passphrase: String): String {
        val plain = com.andrewf13.tuned.data.Backup.export(prefs)
        val enc = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.andrewf13.tuned.data.BackupCrypto.encrypt(plain, passphrase.toCharArray())
        }
        return backups.save(backups.newName(), enc)
    }

    /** Decrypts a backup and makes it the live state: login, favourites, groups, settings. */
    suspend fun restoreBackup(file: ByteArray, passphrase: String) {
        val plain = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.andrewf13.tuned.data.BackupCrypto.decrypt(file, passphrase.toCharArray())
        }
        com.andrewf13.tuned.data.Backup.import(plain, prefs)
        reloadFromPrefs()
    }

    fun openBackup() { screen = Screen.Backup }
    fun closeBackup() { screen = if (prefs.activeProfile != null) Screen.Settings else Screen.Login }

}
