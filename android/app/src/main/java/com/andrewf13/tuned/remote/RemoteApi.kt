package com.andrewf13.tuned.remote

import com.andrewf13.tuned.data.Channel
import com.andrewf13.tuned.data.Programme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/** What the remote API needs from the TV app. Kept small so it's easy to test. */
interface RemoteHost {
    val tvName: String
    val appVersion: String
    fun profileName(): String?
    fun groups(): List<Pair<String, String>>           // id → name, in list order
    fun channels(): List<Channel>                       // everything visible in the TV's list
    fun favourites(): Set<Int>
    fun playing(): Channel?
    fun groupName(id: String?): String?
    suspend fun guide(streamId: Int): List<Programme>
    /** Tune to a channel. Returns false if the TV can't right now. */
    fun tune(streamId: Int): Boolean
    /** Show the pairing code on the TV for [device]. */
    fun showPairingCode(device: String, code: String)
    fun pairingDone(device: String, ok: Boolean)
    fun loadTokens(): Map<String, String>               // token → device
    fun saveTokens(tokens: Map<String, String>)
}

/**
 * The phone remote's API. JSON over HTTP on the local network.
 *
 *   GET  /api/hello                      → TV name, version, whether this phone is paired
 *   POST /api/pair/start  {device}       → shows a 4-digit code on the TV
 *   POST /api/pair/finish {device, code} → {token}
 *   GET  /api/state                      → what's playing                       (token)
 *   GET  /api/channels                   → groups and channels in the TV's list  (token)
 *   GET  /api/guide?ids=1,2,3            → now/next for up to 60 channels        (token)
 *   POST /api/play {id}                  → change channel on the TV              (token)
 *
 * Never sends the provider login.
 */
class RemoteApi(private val host: RemoteHost) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val random = SecureRandom()
    private var tokens: MutableMap<String, String> = host.loadTokens().toMutableMap()

    private var pendingCode: String? = null
    private var pendingDevice: String? = null
    private var pendingUntil = 0L
    private var failedAttempts = 0

    fun handle(req: HttpRequest): HttpResponse {
        val token = req.headers["authorization"]?.removePrefix("Bearer ")?.trim()
        val paired = token != null && synchronized(this) { tokens.containsKey(token) }
        return when {
            req.path == "/api/hello" -> ok(
                "app" to "tuned", "name" to host.tvName, "version" to host.appVersion, "paired" to paired,
            )
            req.path == "/api/pair/start" && req.method == "POST" -> pairStart(req)
            req.path == "/api/pair/finish" && req.method == "POST" -> pairFinish(req)
            !paired -> HttpResponse(401, err("Not paired"))
            req.path == "/api/state" -> HttpResponse(200, state().toString())
            req.path == "/api/channels" -> HttpResponse(200, channels().toString())
            req.path == "/api/guide" -> guide(req)
            req.path == "/api/play" && req.method == "POST" -> play(req)
            req.path == "/api/unpair" && req.method == "POST" -> synchronized(this) { tokens.remove(token); host.saveTokens(tokens); ok("ok" to true) }
            else -> HttpResponse(404, err("Not found"))
        }
    }

    private fun body(req: HttpRequest): JsonObject = runCatching { json.parseToJsonElement(req.body).jsonObject }.getOrElse { JsonObject(emptyMap()) }

    /** Forget every paired phone. */
    @Synchronized
    fun forgetAll() { tokens.clear(); host.saveTokens(tokens) }

    @Synchronized
    fun pairedCount() = tokens.size

    /** For callers already inside a pairing call (which holds the lock). */
    fun pairedCountUnsafe() = tokens.size

    @Synchronized
    private fun pairStart(req: HttpRequest): HttpResponse {
        val device = body(req)["device"]?.jsonPrimitive?.content?.take(40)?.ifBlank { null } ?: "A phone"
        val code = (random.nextInt(9000) + 1000).toString()
        pendingCode = code
        pendingDevice = device
        pendingUntil = System.currentTimeMillis() + 120_000
        failedAttempts = 0
        host.showPairingCode(device, code)
        return ok("ok" to true, "expiresInSec" to 120)
    }

    @Synchronized
    private fun pairFinish(req: HttpRequest): HttpResponse {
        val b = body(req)
        val code = b["code"]?.jsonPrimitive?.content
        val device = b["device"]?.jsonPrimitive?.content?.take(40) ?: pendingDevice ?: "A phone"
        if (pendingCode == null || System.currentTimeMillis() > pendingUntil) {
            return HttpResponse(409, err("No pairing in progress: start again"))
        }
        if (code != pendingCode) {
            failedAttempts++
            if (failedAttempts >= 5) { pendingCode = null; host.pairingDone(device, false) }
            return HttpResponse(403, err("Wrong code"))
        }
        pendingCode = null
        val token = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        tokens[token] = device
        host.saveTokens(tokens)
        host.pairingDone(device, true)
        return ok("token" to token, "name" to host.tvName)
    }

    private fun channelJson(c: Channel, favs: Set<Int>) = JsonObject(
        mapOf(
            "id" to JsonPrimitive(c.streamId), "num" to JsonPrimitive(c.num), "name" to JsonPrimitive(c.name),
            "icon" to (c.icon?.let { JsonPrimitive(it) } ?: JsonNull), "group" to (c.categoryId?.let { JsonPrimitive(it) } ?: JsonNull),
            "fav" to JsonPrimitive(c.streamId in favs),
        ),
    )

    private fun state(): JsonObject {
        val p = host.playing()
        return JsonObject(
            mapOf(
                "name" to JsonPrimitive(host.tvName),
                "profile" to (host.profileName()?.let { JsonPrimitive(it) } ?: JsonNull),
                "playing" to (p?.let { JsonObject(channelJson(it, host.favourites()) + ("groupName" to JsonPrimitive(host.groupName(it.categoryId) ?: ""))) } ?: JsonNull),
            ),
        )
    }

    private fun channels(): JsonObject {
        val favs = host.favourites()
        return JsonObject(
            mapOf(
                "groups" to JsonArray(host.groups().map { (id, name) -> JsonObject(mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(name))) }),
                "channels" to JsonArray(host.channels().map { channelJson(it, favs) }),
            ),
        )
    }

    private fun guide(req: HttpRequest): HttpResponse {
        val ids = req.query["ids"].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.distinct().take(60)
        val out: List<Pair<Int, List<Programme>>> = runBlocking {
            ids.map { id ->
                async(Dispatchers.IO) { id to runCatching { host.guide(id) }.getOrDefault(emptyList()) }
            }.awaitAll()
        }
        return HttpResponse(
            200,
            JsonObject(
                out.associate { (id, progs) ->
                    id.toString() to JsonArray(
                        progs.take(3).map { p: Programme ->
                            JsonObject(mapOf("title" to JsonPrimitive(p.title), "start" to JsonPrimitive(p.startMs), "end" to JsonPrimitive(p.endMs), "desc" to JsonPrimitive(p.description.take(400))))
                        },
                    )
                },
            ).toString(),
        )
    }

    private fun play(req: HttpRequest): HttpResponse {
        val id = body(req)["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: return HttpResponse(400, err("Missing id"))
        if (host.channels().none { it.streamId == id } && host.favourites().none { it == id }) return HttpResponse(404, err("No such channel"))
        if (!host.tune(id)) return HttpResponse(409, err("Tuned isn't on screen on the TV"))
        return HttpResponse(200, state().toString())
    }

    private fun ok(vararg pairs: Pair<String, Any>) = HttpResponse(
        200,
        JsonObject(pairs.associate { (k, v) -> k to when (v) { is Boolean -> JsonPrimitive(v); is Number -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) } }).toString(),
    )

    private fun err(msg: String) = JsonObject(mapOf("error" to JsonPrimitive(msg))).toString()
}
