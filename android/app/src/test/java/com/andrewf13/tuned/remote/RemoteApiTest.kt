package com.andrewf13.tuned.remote

import com.andrewf13.tuned.data.Channel
import com.andrewf13.tuned.data.Programme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteApiTest {
    private class FakeTv : RemoteHost {
        override val tvName = "Tuned on SHIELD"
        override val appVersion = "test"
        var code: String? = null
        var tuned: Int? = null
        var stored: Map<String, String> = emptyMap()
        val list = listOf(
            Channel(100, 1, "Fox Footy HD", null, "1", null),
            Channel(101, 2, "Sky Sport 1 NZ", "http://logo", "2", null),
        )
        override fun profileName() = "My provider"
        override fun groups() = listOf("1" to "AU | Sport", "2" to "NZ | Sport")
        override fun channels() = list
        override fun favourites() = setOf(101)
        override fun playing() = list.firstOrNull { it.streamId == tuned }
        override fun groupName(id: String?) = groups().firstOrNull { it.first == id }?.second
        override suspend fun guide(streamId: Int) = listOf(Programme("Super Rugby: Blues v Chiefs", "", 1_000, 2_000))
        override fun tune(streamId: Int): Boolean { tuned = streamId; return true }
        override fun showPairingCode(device: String, code: String) { this.code = code }
        override fun pairingDone(device: String, ok: Boolean) {}
        override fun loadTokens() = stored
        override fun saveTokens(tokens: Map<String, String>) { stored = tokens.toMap() }
    }

    private val tv = FakeTv()
    private val server = MiniHttpServer(RemoteApi(tv)::handle)
    private val http = OkHttpClient()
    private var base = ""

    @Before fun start() { base = "http://127.0.0.1:${server.start(0)}" }
    @After fun stop() = server.stop()

    private fun call(path: String, body: String? = null, token: String? = null): Pair<Int, String> {
        val b = Request.Builder().url(base + path)
        if (body != null) b.post(body.toRequestBody("application/json".toMediaType()))
        if (token != null) b.header("Authorization", "Bearer $token")
        return http.newCall(b.build()).execute().use { it.code to it.body!!.string() }
    }

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun pairThenBrowseAndChangeChannel() {
        val hello = json(call("/api/hello").second)
        assertEquals("Tuned on SHIELD", hello["name"]!!.jsonPrimitive.content)
        assertEquals("false", hello["paired"]!!.jsonPrimitive.content)

        assertEquals(401, call("/api/channels").first)            // locked until paired
        assertEquals(200, call("/api/pair/start", """{"device":"Galaxy S25"}""").first)
        val code = tv.code!!
        assertEquals(4, code.length)
        val wrong = if (code == "1111") "2222" else "1111"
        assertEquals(403, call("/api/pair/finish", """{"device":"Galaxy S25","code":"$wrong"}""").first)
        val (status, body) = call("/api/pair/finish", """{"device":"Galaxy S25","code":"$code"}""")
        assertEquals(200, status)
        val token = json(body)["token"]!!.jsonPrimitive.content
        assertEquals(mapOf(token to "Galaxy S25"), tv.stored)

        val ch = json(call("/api/channels", token = token).second)
        assertEquals(2, ch["channels"]!!.jsonArray.size)
        assertEquals(2, ch["groups"]!!.jsonArray.size)
        val guide = json(call("/api/guide?ids=100,101", token = token).second)
        assertEquals("Super Rugby: Blues v Chiefs", guide["100"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)

        val played = json(call("/api/play", """{"id":101}""", token).second)
        assertEquals(101, tv.tuned)
        assertEquals(101, played["playing"]!!.jsonObject["id"]!!.jsonPrimitive.int)
        assertEquals(404, call("/api/play", """{"id":999}""", token).first)
    }

    @Test fun codeStopsWorkingAfterFiveWrongGuesses() {
        call("/api/pair/start", """{"device":"x"}""")
        val code = tv.code!!
        repeat(5) { call("/api/pair/finish", """{"code":"0000"}""") }
        assertTrue(call("/api/pair/finish", """{"code":"$code"}""").first in listOf(403, 409))
    }

    @Test fun neverLeaksTheLogin() {
        call("/api/pair/start", """{"device":"x"}""")
        val token = json(call("/api/pair/finish", """{"code":"${tv.code}"}""").second)["token"]!!.jsonPrimitive.content
        val everything = listOf("/api/state", "/api/channels", "/api/guide?ids=100").joinToString { call(it, token = token).second }
        assertFalse(everything.contains("password", ignoreCase = true))
        assertFalse(everything.contains("username", ignoreCase = true))
    }
}
