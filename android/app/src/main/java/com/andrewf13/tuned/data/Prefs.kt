package com.andrewf13.tuned.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What's remembered per provider profile: these ids only mean something for that provider. */
class ProfileState(private val sp: SharedPreferences) {
    var favourites: Set<Int>
        get() = sp.getStringSet("favs", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = sp.edit().putStringSet("favs", v.map { it.toString() }.toSet()).apply()

    /** Channels the user has unticked inside their groups. */
    var hiddenChannels: Set<Int>
        get() = sp.getStringSet("hiddenChannels", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = sp.edit().putStringSet("hiddenChannels", v.map { it.toString() }.toSet()).apply()

    /** Group ids to show, or null for "all groups" (new provider groups then appear automatically). */
    var visibleGroups: Set<String>?
        get() = if (sp.getBoolean("groupFilterOn", false)) sp.getStringSet("visibleGroups", emptySet())!!.toSet() else null
        set(v) {
            val e = sp.edit()
            if (v == null) e.putBoolean("groupFilterOn", false).remove("visibleGroups")
            else e.putBoolean("groupFilterOn", true).putStringSet("visibleGroups", v)
            e.apply()
        }

    /** Most recent first; the first entry is usually the channel playing now. */
    var recents: List<Int>
        get() = sp.getString("recents", "")!!.split(',').mapNotNull { it.toIntOrNull() }
        set(v) = sp.edit().putString("recents", v.joinToString(",")).apply()

    var lastStreamId: Int
        get() = sp.getInt("lastStream", -1)
        set(v) = sp.edit().putInt("lastStream", v).apply()

    var lastCategory: String
        get() = sp.getString("lastCategory", "all")!!
        set(v) = sp.edit().putString("lastCategory", v).apply()

    fun clear() {
        sp.edit().remove("favs").remove("hiddenChannels").remove("groupFilterOn").remove("visibleGroups")
            .remove("recents").remove("lastStream").remove("lastCategory").apply()
    }
}

/**
 * App settings (shared by every profile), the profile list, and the active profile's state.
 * The first profile ("default") keeps its state in the main file, so upgrades from
 * single-login versions keep their favourites without a migration step.
 */
class Prefs(private val context: Context) {
    private val sp = context.getSharedPreferences("tuned", Context.MODE_PRIVATE)

    init { migrateSingleAccount() }

    private fun migrateSingleAccount() {
        val server = sp.getString("server", null) ?: return
        if (profiles.isEmpty()) {
            profiles = listOf(
                Profile("default", "My provider", SourceType.XTREAM, server, sp.getString("user", "")!!, sp.getString("pass", "")!!),
            )
            activeProfileId = "default"
        }
        sp.edit().remove("server").remove("user").remove("pass").apply()
    }

    // ---- Profiles -----------------------------------------------------------------------------
    var profiles: List<Profile>
        get() = runCatching {
            XtreamParse.json.parseToJsonElement(sp.getString("profiles", "[]")!!).jsonArray.map { profileFrom(it.jsonObject) }
        }.getOrDefault(emptyList())
        set(v) = sp.edit().putString("profiles", JsonArray(v.map { profileJson(it) }).toString()).apply()

    var activeProfileId: String?
        get() = sp.getString("activeProfile", null)
        set(v) = sp.edit().putString("activeProfile", v).apply()

    val activeProfile: Profile? get() = profiles.firstOrNull { it.id == activeProfileId }

    fun upsertProfile(p: Profile) {
        val list = profiles
        profiles = if (list.any { it.id == p.id }) list.map { if (it.id == p.id) p else it } else list + p
    }

    fun removeProfile(id: String) {
        state(id).clear()
        if (id != "default") context.deleteSharedPreferences(fileFor(id))
        profiles = profiles.filter { it.id != id }
        if (activeProfileId == id) activeProfileId = profiles.firstOrNull()?.id
    }

    private fun fileFor(id: String) = "tuned_profile_$id"
    fun state(id: String?): ProfileState =
        ProfileState(if (id == null || id == "default") sp else context.getSharedPreferences(fileFor(id), Context.MODE_PRIVATE))

    /** State of the active profile. */
    val current: ProfileState get() = state(activeProfileId)

    // ---- App settings -------------------------------------------------------------------------
    var afrEnabled: Boolean
        get() = sp.getBoolean("afr", true)
        set(v) = sp.edit().putBoolean("afr", v).apply()

    /** "ts" or "m3u8" (Xtream only; M3U playlists give their own URLs). */
    var streamFormat: String
        get() = sp.getString("format", "ts")!!
        set(v) = sp.edit().putString("format", v).apply()

    var resumeOnStart: Boolean
        get() = sp.getBoolean("resume", true)
        set(v) = sp.edit().putBoolean("resume", v).apply()

    var sortAlpha: Boolean
        get() = sp.getBoolean("sortAlpha", false)
        set(v) = sp.edit().putBoolean("sortAlpha", v).apply()

    var groupSortAlpha: Boolean
        get() = sp.getBoolean("groupSortAlpha", false)
        set(v) = sp.edit().putBoolean("groupSortAlpha", v).apply()

    var themeId: String
        get() = sp.getString("theme", "blue")!!
        set(v) = sp.edit().putString("theme", v).apply()

    var volumeBoostDb: Int
        get() = sp.getInt("boostDb", 6)
        set(v) = sp.edit().putInt("boostDb", v).apply()

    /** Paired phones: token → device name. */
    var remoteTokens: Map<String, String>
        get() = runCatching {
            XtreamParse.json.parseToJsonElement(sp.getString("remoteTokens", "{}")!!).jsonObject.mapValues { it.value.jsonPrimitive.content }
        }.getOrDefault(emptyMap())
        set(v) = sp.edit().putString("remoteTokens", JsonObject(v.mapValues { JsonPrimitive(it.value) }).toString()).apply()

    /** Community guide countries from epgshare01 (codes like "AU"). */
    var guideCountries: Set<String>
        get() = sp.getStringSet("guideCountries", setOf("AU", "NZ"))!!.toSet()
        set(v) = sp.edit().putStringSet("guideCountries", v).apply()

    var guideFromProvider: Boolean
        get() = sp.getBoolean("guideProvider", true)
        set(v) = sp.edit().putBoolean("guideProvider", v).apply()

    /** "small", "normal" or "large". */
    var bufferMode: String
        get() = sp.getString("buffer", "normal")!!
        set(v) = sp.edit().putString("buffer", v).apply()

    companion object {
        fun profileJson(p: Profile) = JsonObject(
            mapOf(
                "id" to JsonPrimitive(p.id), "name" to JsonPrimitive(p.name), "type" to JsonPrimitive(p.type.name),
                "server" to JsonPrimitive(p.server), "username" to JsonPrimitive(p.username),
                "password" to JsonPrimitive(p.password), "m3uUrl" to JsonPrimitive(p.m3uUrl),
            ),
        )

        fun profileFrom(o: JsonObject) = Profile(
            id = o["id"]!!.jsonPrimitive.content,
            name = o["name"]?.jsonPrimitive?.content ?: "Provider",
            type = runCatching { SourceType.valueOf(o["type"]!!.jsonPrimitive.content) }.getOrDefault(SourceType.XTREAM),
            server = o["server"]?.jsonPrimitive?.content ?: "",
            username = o["username"]?.jsonPrimitive?.content ?: "",
            password = o["password"]?.jsonPrimitive?.content ?: "",
            m3uUrl = o["m3uUrl"]?.jsonPrimitive?.content ?: "",
        )
    }
}
