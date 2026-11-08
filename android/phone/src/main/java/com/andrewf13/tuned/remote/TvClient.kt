package com.andrewf13.tuned.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class TvChannel(val id: Int, val num: Int, val name: String, val icon: String?, val group: String?, val fav: Boolean)
data class TvGroup(val id: String, val name: String)
data class GuideItem(val title: String, val startMs: Long, val endMs: Long, val desc: String) {
    fun isOn(now: Long) = now in startMs until endMs
    fun progress(now: Long) = ((now - startMs).toFloat() / (endMs - startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
}
data class TvState(val name: String, val profile: String?, val playing: TvChannel?, val playingGroup: String?)
data class Lineup(val groups: List<TvGroup>, val channels: List<TvChannel>)

class TvException(val status: Int, message: String) : Exception(message)

/** Talks to the Tuned app on the TV (see the TV app's RemoteApi). */
class TvClient(val host: String, val port: Int, var token: String? = null) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val base get() = "http://${if (host.contains(':')) "[$host]" else host}:$port"

    private suspend fun call(path: String, body: JsonObject? = null): JsonObject = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(base + path)
        if (body != null) b.post(body.toString().toRequestBody("application/json".toMediaType()))
        token?.let { b.header("Authorization", "Bearer $it") }
        http.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { JsonObject(emptyMap()) }
            if (!r.isSuccessful) throw TvException(r.code, o["error"]?.jsonPrimitive?.content ?: "TV replied ${r.code}")
            o
        }
    }

    private fun obj(vararg p: Pair<String, Any>) =
        JsonObject(p.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) })

    suspend fun hello(): Pair<String, Boolean> = call("/api/hello").let {
        it["name"]!!.jsonPrimitive.content to (it["paired"]?.jsonPrimitive?.boolean ?: false)
    }

    suspend fun pairStart(device: String) { call("/api/pair/start", obj("device" to device)) }

    /** Returns the token. */
    suspend fun pairFinish(device: String, code: String): String =
        call("/api/pair/finish", obj("device" to device, "code" to code))["token"]!!.jsonPrimitive.content.also { token = it }

    private fun channel(o: JsonObject) = TvChannel(
        id = o["id"]!!.jsonPrimitive.int,
        num = o["num"]?.jsonPrimitive?.int ?: 0,
        name = o["name"]!!.jsonPrimitive.content,
        icon = (o["icon"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
        group = (o["group"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
        fav = o["fav"]?.jsonPrimitive?.boolean ?: false,
    )

    suspend fun lineup(): Lineup = call("/api/channels").let { o ->
        Lineup(
            o["groups"]!!.jsonArray.map { val g = it.jsonObject; TvGroup(g["id"]!!.jsonPrimitive.content, g["name"]!!.jsonPrimitive.content) },
            o["channels"]!!.jsonArray.map { channel(it.jsonObject) },
        )
    }

    suspend fun guide(ids: List<Int>): Map<Int, List<GuideItem>> {
        if (ids.isEmpty()) return emptyMap()
        val o = call("/api/guide?ids=" + ids.take(60).joinToString(","))
        return o.entries.associate { (k, v) ->
            k.toInt() to (v as JsonArray).map {
                val g = it.jsonObject
                GuideItem(g["title"]!!.jsonPrimitive.content, g["start"]!!.jsonPrimitive.long, g["end"]!!.jsonPrimitive.long, g["desc"]?.jsonPrimitive?.content.orEmpty())
            }
        }
    }

    private fun state(o: JsonObject) = TvState(
        name = o["name"]?.jsonPrimitive?.content ?: "TV",
        profile = (o["profile"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
        playing = (o["playing"] as? JsonObject)?.let { channel(it) },
        playingGroup = ((o["playing"] as? JsonObject)?.get("groupName") as? JsonPrimitive)?.content,
    )

    suspend fun state(): TvState = state(call("/api/state"))
    suspend fun play(id: Int): TvState = state(call("/api/play", obj("id" to id)))
    suspend fun unpair() { runCatching { call("/api/unpair", JsonObject(emptyMap())) } }
}
