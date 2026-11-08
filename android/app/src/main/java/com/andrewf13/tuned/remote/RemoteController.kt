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
