package com.andrewf13.tuned.remote

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** A pretend TV speaking the same API as Tuned's RemoteApi. */
private class FakeTv : Dispatcher() {
    var playing = 101
    val plays = mutableListOf<Int>()
    private val now = System.currentTimeMillis()
    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path!!
        val authed = request.getHeader("Authorization") == "Bearer t0k3n"
        fun json(s: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(s)
        return when {
            path == "/api/hello" -> json("""{"app":"tuned","name":"Tuned on SHIELD Android TV","version":"0.7.0","paired":$authed}""")
            path == "/api/pair/start" -> json("""{"ok":true}""")
            path == "/api/pair/finish" -> if (request.body.readUtf8().contains("\"4821\"")) json("""{"token":"t0k3n"}""") else json("""{"error":"Wrong code"}""", 403)
            !authed -> json("""{"error":"Not paired"}""", 401)
            path == "/api/channels" -> json(
                """{"groups":[{"id":"1","name":"AU | Sport"},{"id":"2","name":"NZ | Sport"},{"id":"3","name":"UK | Sport"}],
                "channels":[
                 {"id":100,"num":1,"name":"Fox Footy HD","icon":null,"group":"1","fav":true},
                 {"id":101,"num":2,"name":"Fox League HD","icon":null,"group":"1","fav":true},
                 {"id":102,"num":3,"name":"Fox Cricket HD","icon":null,"group":"1","fav":false},
                 {"id":104,"num":5,"name":"Sky Sport 1 NZ","icon":null,"group":"2","fav":true},
                 {"id":105,"num":6,"name":"Sky Sport 2 NZ","icon":null,"group":"2","fav":false},
                 {"id":112,"num":13,"name":"Sky Sports F1 UHD","icon":null,"group":"3","fav":true}]}""",
            )
            path.startsWith("/api/guide") -> {
                val titles = mapOf(100 to "AFL: Swans v Giants", 101 to "NRL: Panthers v Storm", 104 to "Super Rugby: Blues v Chiefs", 112 to "F1: Las Vegas GP Qualifying")
                val ids = path.substringAfter("ids=").split(',').mapNotNull { it.toIntOrNull() }
                json(ids.joinToString(",", "{", "}") { id ->
                    val t = titles[id]
                    "\"$id\":" + if (t == null) "[]" else
                        """[{"title":"$t","start":${now - 40 * 60_000},"end":${now + 50 * 60_000},"desc":""},{"title":"Post-match","start":${now + 50 * 60_000},"end":${now + 80 * 60_000},"desc":""}]"""
                })
            }
            path == "/api/state" || path == "/api/play" -> {
                if (path == "/api/play") { playing = Regex("\\d+").find(request.body.readUtf8())!!.value.toInt(); plays += playing }
                json("""{"name":"Tuned on SHIELD Android TV","profile":"My provider","playing":{"id":$playing,"num":2,"name":"${if (playing == 101) "Fox League HD" else "Sky Sport 1 NZ"}","icon":null,"group":"1","fav":true}}""")
            }
            else -> json("""{"error":"Not found"}""", 404)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi") // Galaxy S25: 1080 × 2340
class PhoneTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val tv = FakeTv()
    private val server = MockWebServer().apply { dispatcher = tv }

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        File("build/screenshots").apply { mkdirs() }.resolve("$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun idleUntil(cond: () -> Boolean) {
        repeat(200) {
            ShadowLooper.idleMainLooper()
            runCatching { compose.waitForIdle() } // lets Compose lay out, so on-screen rows ask for their guide
            if (cond()) return
            Thread.sleep(25)
        }
        error("condition never met")
    }

    @Test fun clientPairsAndChangesChannel() = runBlocking {
        val c = TvClient("127.0.0.1", server.port)
        assertEquals("Tuned on SHIELD Android TV", c.hello().first)
        c.pairStart("Galaxy S25")
        val wrong = runCatching { c.pairFinish("Galaxy S25", "0000") }.exceptionOrNull() as TvException
        assertEquals(403, wrong.status)
        assertEquals("t0k3n", c.pairFinish("Galaxy S25", "4821"))
        val l = c.lineup()
        assertEquals(6, l.channels.size)
        assertEquals("Super Rugby: Blues v Chiefs", c.guide(listOf(104))[104]!![0].title)
        assertEquals(104, c.play(104).playing?.id)
        assertEquals(listOf(104), tv.plays)
    }

    @Test fun channelsScreen() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        RemoteStore(app).tv = SavedTv("Tuned on SHIELD Android TV", "127.0.0.1", server.port, "t0k3n")
        val vm = RemoteViewModel(app)
        idleUntil { vm.lineup.channels.isNotEmpty() && vm.state != null }
        compose.setContent { RemoteTheme { RemoteApp(vm) } }
        idleUntil { vm.guide.containsKey(104) }
        shot("phone-favourites")
        compose.runOnUiThread { vm.chooseFilter("g:2") }
        idleUntil { vm.guide.containsKey(105) }
        shot("phone-group")
        compose.runOnUiThread { vm.play(vm.lineup.channels.first { it.id == 104 }) }
        idleUntil { vm.state?.playing?.id == 104 }
        assertTrue(104 in tv.plays)
    }

    @Test fun pairScreen() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        RemoteStore(app).tv = null
        val vm = RemoteViewModel(app)
        compose.setContent { RemoteTheme { RemoteApp(vm) } }
        compose.runOnUiThread { vm.startPairing(FoundTv("Tuned on SHIELD Android TV", "127.0.0.1", server.port)) }
        idleUntil { vm.page == Page.Pair }
        shot("phone-pair")
        compose.runOnUiThread { vm.finishPairing("4821") {} }
        idleUntil { vm.page == Page.Channels }
        assertEquals("t0k3n", RemoteStore(app).tv?.token)
    }
}
