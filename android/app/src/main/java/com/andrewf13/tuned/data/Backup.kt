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

/** What goes in a backup: login, favourites, group choice, recents and settings. */
object Backup {
    fun export(prefs: Prefs): ByteArray {
        val a = prefs.account ?: throw XtreamException("Sign in before making a backup")
        val obj = JsonObject(
            mapOf(
                "app" to JsonPrimitive("tuned"),
                "created" to JsonPrimitive(System.currentTimeMillis()),
                "account" to JsonObject(
                    mapOf(
                        "server" to JsonPrimitive(a.server),
                        "username" to JsonPrimitive(a.username),
                        "password" to JsonPrimitive(a.password),
                    ),
                ),
                "favourites" to JsonArray(prefs.favourites.sorted().map { JsonPrimitive(it) }),
                "visibleGroups" to (prefs.visibleGroups?.let { g -> JsonArray(g.sorted().map { JsonPrimitive(it) }) } ?: JsonNull),
                "recents" to JsonArray(prefs.recents.map { JsonPrimitive(it) }),
                "hiddenChannels" to JsonArray(prefs.hiddenChannels.sorted().map { JsonPrimitive(it) }),
                "lastCategory" to JsonPrimitive(prefs.lastCategory),
                "settings" to JsonObject(
                    mapOf(
                        "afr" to JsonPrimitive(prefs.afrEnabled),
                        "format" to JsonPrimitive(prefs.streamFormat),
                        "resume" to JsonPrimitive(prefs.resumeOnStart),
                        "sortAlpha" to JsonPrimitive(prefs.sortAlpha),
                        "groupSortAlpha" to JsonPrimitive(prefs.groupSortAlpha),
                        "boostDb" to JsonPrimitive(prefs.volumeBoostDb),
                        "theme" to JsonPrimitive(prefs.themeId),
                    ),
                ),
            ),
        )
        return obj.toString().toByteArray()
    }

    /** Writes the backup into [prefs]. Returns the account it contained. */
    fun import(bytes: ByteArray, prefs: Prefs): Account {
        val o = XtreamParse.json.parseToJsonElement(bytes.decodeToString()).jsonObject
        val acc = o["account"]!!.jsonObject
        val account = Account(
            acc["server"]!!.jsonPrimitive.content,
            acc["username"]!!.jsonPrimitive.content,
            acc["password"]!!.jsonPrimitive.content,
        )
        prefs.account = account
        prefs.favourites = o["favourites"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toIntOrNull() }?.toSet() ?: emptySet()
        prefs.visibleGroups = (o["visibleGroups"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet()
        prefs.hiddenChannels = o["hiddenChannels"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toIntOrNull() }?.toSet() ?: emptySet()
        prefs.recents = o["recents"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toIntOrNull() } ?: emptyList()
        o["lastCategory"]?.jsonPrimitive?.content?.let { prefs.lastCategory = it }
        (o["settings"] as? JsonObject)?.let { s ->
            s["afr"]?.let { prefs.afrEnabled = it.jsonPrimitive.boolean }
            s["format"]?.let { prefs.streamFormat = it.jsonPrimitive.content }
            s["resume"]?.let { prefs.resumeOnStart = it.jsonPrimitive.boolean }
            s["sortAlpha"]?.let { prefs.sortAlpha = it.jsonPrimitive.boolean }
            s["groupSortAlpha"]?.let { prefs.groupSortAlpha = it.jsonPrimitive.boolean }
            s["boostDb"]?.let { prefs.volumeBoostDb = it.jsonPrimitive.int }
            s["theme"]?.let { prefs.themeId = it.jsonPrimitive.content }
        }
        return account
    }
}
