package com.andrewf13.tuned.remote

import android.os.Build
import android.os.Handler
import android.os.Looper
import com.andrewf13.tuned.AppViewModel
import com.andrewf13.tuned.BuildConfig
import com.andrewf13.tuned.data.Channel
import com.andrewf13.tuned.data.Programme
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs the phone-remote server while Tuned is on screen, and bridges it to the app. */
class RemoteController(private val vm: AppViewModel, private val discovery: RemoteDiscovery) : RemoteHost {
    private val main = Handler(Looper.getMainLooper())
    val api = RemoteApi(this)
    private val server = MiniHttpServer(api::handle)

    override val tvName = "Tuned on " + (Build.MODEL?.takeIf { it.isNotBlank() } ?: "TV")
    override val appVersion: String = BuildConfig.VERSION_NAME

    fun start() {
        val port = server.start()
        discovery.register(tvName, port)
        vm.remoteAddress = localIp()?.let { "$it:$port" }
        vm.pairedPhones = api.pairedCount()
    }

    fun stop() {
        discovery.unregister()
        server.stop()
    }

    fun forgetPhones() { api.forgetAll(); vm.pairedPhones = 0 }

    /** Run on the main thread and wait (the view model's state belongs to the UI thread). */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        main.post { result = runCatching(block); done.countDown() }
        done.await(5, TimeUnit.SECONDS)
        return result?.getOrThrow() ?: throw IllegalStateException("TV is busy")
    }

    override fun profileName() = onMain { vm.profiles.firstOrNull { it.id == vm.activeProfileId }?.name }
    override fun groups() = onMain { vm.categories.map { it.id to it.name } }
    override fun channels(): List<Channel> = onMain { vm.remoteChannels() }
    override fun allChannels(): List<Channel> = onMain { vm.channels }

    override fun sports(sports: Set<String>, keywords: List<String>, replays: Boolean): SportsResult? {
        val store = vm.repo.guide ?: return null
        if (store.updatedAt == 0L) return null
        val now = System.currentTimeMillis()
        val rows = store.sports(sports, keywords, replays, now, now + 7 * 86_400_000L)
        val (all, preferred) = onMain { vm.channels.associateBy { it.streamId } to (vm.remoteChannels().map { it.streamId }.toSet() + vm.favourites) }
        val kw = keywords.map { it.trim() }.filter { it.isNotEmpty() }
        // One event per programme title + start time, gathering every channel showing it.
        val grouped = rows.groupBy { "${it.row.title.lowercase()}|${it.row.sub?.lowercase()}|${it.row.startMs / 600_000}" }
        val events = grouped.values.map { group ->
            val r = group.first().row
            val chans = group.flatMap { it.streams }.distinct().mapNotNull { all[it] }
                .sortedWith(compareByDescending<Channel> { it.streamId in preferred }.thenBy { it.name.length })
                .take(6)
            SportsEvent(
                r.title, r.sub, group.firstNotNullOfOrNull { it.row.sport }, r.startMs, r.stopMs,
                group.any { it.row.live }, group.all { it.row.replay },
                kw.any { k -> r.title.contains(k, true) || r.sub.orEmpty().contains(k, true) }, chans,
            )
        }.filter { it.channels.isNotEmpty() }.sortedWith(compareBy({ it.startMs }, { it.title })).take(400)
        return SportsResult(store.updatedAt, events)
    }
    override fun favourites() = onMain { vm.favourites }
    override fun playing() = onMain { vm.playing }
    override fun groupName(id: String?) = onMain { vm.groupNameFor(id) }
    override suspend fun guide(streamId: Int): List<Programme> = vm.repo.epg(streamId)
    override fun tune(streamId: Int) = onMain { vm.tuneFromRemote(streamId) }
    // Posted, not waited for: these are called while the API holds its pairing lock.
    override fun showPairingCode(device: String, code: String) { main.post { vm.pairing = device to code } }
    override fun pairingDone(device: String, ok: Boolean) {
        val count = api.pairedCountUnsafe()
        main.post {
            vm.pairing = null
            vm.pairedPhones = count
            if (ok) vm.status = "Paired with $device"
        }
    }
    override fun loadTokens() = vm.prefs.remoteTokens
    override fun saveTokens(tokens: Map<String, String>) { vm.prefs.remoteTokens = tokens }

    private fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()
}
