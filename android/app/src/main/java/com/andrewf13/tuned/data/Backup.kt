package com.andrewf13.tuned.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What goes in a backup: every profile (login or playlist link) with its favourites,
 * groups, unticked channels and recents, plus the app settings.
 * Version 1 (single login, Tuned 0.4–0.5) is still read.
 */
object Backup {
    fun export(prefs: Prefs): ByteArray {
        val profiles = prefs.profiles
        if (profiles.isEmpty()) throw XtreamException("Add a provider before making a backup")
        val obj = JsonObject(
            mapOf(
                "app" to JsonPrimitive("tuned"),
                "version" to JsonPrimitive(2),
                "created" to JsonPrimitive(System.currentTimeMillis()),
                "activeProfile" to JsonPrimitive(prefs.activeProfileId),
                "profiles" to JsonArray(
                    profiles.map { p ->
                        JsonObject(Prefs.profileJson(p) + ("state" to stateJson(prefs.state(p.id))))
                    },
                ),
                "settings" to JsonObject(
                    mapOf(
                        "afr" to JsonPrimitive(prefs.afrEnabled),
                        "format" to JsonPrimitive(prefs.streamFormat),
                        "resume" to JsonPrimitive(prefs.resumeOnStart),
                        "sortAlpha" to JsonPrimitive(prefs.sortAlpha),
                        "groupSortAlpha" to JsonPrimitive(prefs.groupSortAlpha),
                        "boostDb" to JsonPrimitive(prefs.volumeBoostDb),
                        "theme" to JsonPrimitive(prefs.themeId),
                        "buffer" to JsonPrimitive(prefs.bufferMode),
                    ),
                ),
            ),
        )
        return obj.toString().toByteArray()
    }

    private fun stateJson(st: ProfileState) = JsonObject(
        mapOf(
            "favourites" to JsonArray(st.favourites.sorted().map { JsonPrimitive(it) }),
            "hiddenChannels" to JsonArray(st.hiddenChannels.sorted().map { JsonPrimitive(it) }),
            "visibleGroups" to (st.visibleGroups?.let { g -> JsonArray(g.sorted().map { JsonPrimitive(it) }) } ?: JsonNull),
            "recents" to JsonArray(st.recents.map { JsonPrimitive(it) }),
            "lastCategory" to JsonPrimitive(st.lastCategory),
        ),
    )

    private fun readState(o: JsonObject, st: ProfileState) {
        st.favourites = o["favourites"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toIntOrNull() }?.toSet() ?: emptySet()
        st.hiddenChannels = o["hiddenChannels"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toIntOrNull() }?.toSet() ?: emptySet()
        st.visibleGroups = (o["visibleGroups"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet()
        st.recents = o["recents"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toIntOrNull() } ?: emptyList()
        o["lastCategory"]?.jsonPrimitive?.content?.let { st.lastCategory = it }
    }

    /** Replaces this device's profiles and settings with the backup's. Returns how many profiles it held. */
    fun import(bytes: ByteArray, prefs: Prefs): Int {
        val o = XtreamParse.json.parseToJsonElement(bytes.decodeToString()).jsonObject
        prefs.profiles.forEach { prefs.removeProfile(it.id) }
        if (o["profiles"] is JsonArray) {
            o["profiles"]!!.jsonArray.forEach { el ->
                val po = el.jsonObject
                val p = Prefs.profileFrom(po)
                prefs.upsertProfile(p)
                (po["state"] as? JsonObject)?.let { readState(it, prefs.state(p.id)) }
            }
            prefs.activeProfileId = o["activeProfile"]?.jsonPrimitive?.content?.takeIf { id -> prefs.profiles.any { it.id == id } }
                ?: prefs.profiles.firstOrNull()?.id
        } else {
            // Version 1: one Xtream login, state at the top level.
            val acc = o["account"]!!.jsonObject
            val p = Profile(
                "default", "My provider", SourceType.XTREAM,
                acc["server"]!!.jsonPrimitive.content, acc["username"]!!.jsonPrimitive.content, acc["password"]!!.jsonPrimitive.content,
            )
            prefs.upsertProfile(p)
            readState(o, prefs.state("default"))
            prefs.activeProfileId = "default"
        }
        (o["settings"] as? JsonObject)?.let { s ->
            s["afr"]?.let { prefs.afrEnabled = it.jsonPrimitive.boolean }
            s["format"]?.let { prefs.streamFormat = it.jsonPrimitive.content }
            s["resume"]?.let { prefs.resumeOnStart = it.jsonPrimitive.boolean }
            s["sortAlpha"]?.let { prefs.sortAlpha = it.jsonPrimitive.boolean }
            s["groupSortAlpha"]?.let { prefs.groupSortAlpha = it.jsonPrimitive.boolean }
            s["boostDb"]?.let { prefs.volumeBoostDb = it.jsonPrimitive.int }
            s["theme"]?.let { prefs.themeId = it.jsonPrimitive.content }
            s["buffer"]?.let { prefs.bufferMode = it.jsonPrimitive.content }
        }
        return prefs.profiles.size
    }
}
