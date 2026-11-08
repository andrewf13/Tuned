package com.andrewf13.tuned.remote.sports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** One row of TheSportsDB's TV listings: one event on one channel. */
data class Listing(
    val eventId: String,
    val event: String,
    val sport: String,
    val channel: String,
    val channelCountry: String,
    val startMs: Long,
    val thumb: String?,
    val channelLogo: String?,
)

class SportsDbException(val status: Int, message: String) : Exception(message)

/**
 * TheSportsDB TV listings (https://www.thesportsdb.com).
 * Premium key → v2 `filter/tv/sport/{sport}` (about a week ahead, header X-API-KEY).
 * The free test key "123" → v1 `eventstv.php`, which returns only one listing per request.
 * Times in the API are UTC ("2026-10-10 11:30:00").
 */
class SportsDb(private val key: String, private val base: String = "https://www.thesportsdb.com") {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    val isFreeKey get() = key.isBlank() || key == FREE_KEY

    private suspend fun get(url: String, header: Boolean): JsonObject? = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url)
        if (header) b.header("X-API-KEY", key)
        http.newCall(b.build()).execute().use { r ->
            if (r.code == 400 || r.code == 401 || r.code == 403) throw SportsDbException(r.code, "TheSportsDB didn't accept the key")
            if (r.code == 429) throw SportsDbException(429, "TheSportsDB is busy (rate limit). Try again in a minute")
            if (!r.isSuccessful) throw SportsDbException(r.code, "TheSportsDB replied ${r.code}")
            val text = r.body?.string().orEmpty()
            if (text.isBlank()) null else runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        }
    }

    /** Upcoming TV listings for one sport (TheSportsDB's sport name, e.g. "Australian Football"). */
    suspend fun listings(sport: String, days: List<String>): List<Listing> {
        return if (isFreeKey) {
            days.flatMap { day ->
                val url = "$base/api/v1/json/${key.ifBlank { FREE_KEY }}/eventstv.php?d=$day&s=" + URLEncoder.encode(sport.replace(' ', '_'), "UTF-8")
                parse(get(url, header = false), "tvevents")
            }
        } else {
            val url = "$base/api/v2/json/filter/tv/sport/" + URLEncoder.encode(sport, "UTF-8").replace("+", "%20")
            parse(get(url, header = true), "filter")
        }
    }

    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    fun parse(root: JsonObject?, key: String): List<Listing> {
        val arr = root?.get(key) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val start = parseUtc(str(o, "strTimeStamp")) ?: parseUtc(listOfNotNull(str(o, "dateEvent"), str(o, "strTime")).joinToString(" ")) ?: return@mapNotNull null
            Listing(
                eventId = str(o, "idEvent") ?: "${str(o, "strEvent")}@$start",
                event = str(o, "strEvent") ?: return@mapNotNull null,
                sport = str(o, "strSport") ?: "",
                channel = str(o, "strChannel") ?: return@mapNotNull null,
                channelCountry = str(o, "strCountry") ?: "",
                startMs = start,
                thumb = str(o, "strEventThumb") ?: str(o, "strEventSquare"),
                channelLogo = str(o, "strLogo"),
            )
        }
    }

    companion object {
        const val FREE_KEY = "123"

        fun parseUtc(s: String?): Long? {
            val t = s?.trim()?.replace('T', ' ')?.removeSuffix("Z")?.substringBefore('+') ?: return null
            for (p in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")) {
                runCatching {
                    return SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }.parse(t)!!.time
                }
            }
            return null
        }

        fun utcDays(fromMs: Long, count: Int): List<String> {
            val f = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            return (0 until count).map { f.format(fromMs + it * 86_400_000L) }
        }
    }
}
