package com.andrewf13.tuned.data

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class Probe(val ok: Boolean, val ms: Long?, val detail: String? = null)

enum class Verdict { NO_INTERNET, PROVIDER_DOWN, ACCOUNT, SLOW_STREAM, ALL_GOOD }

data class Diagnosis(val internet: Probe, val provider: Probe, val verdict: Verdict, val headline: String, val advice: String)

/**
 * Works out why a stream is struggling: is it the internet, the provider's server,
 * the account, or just this stream? Each check is a small, quick request.
 */
class NetworkDoctor(base: OkHttpClient) {
    private val http = base.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS)
        .build()

    private suspend fun timed(url: String, head: Boolean = false): Probe = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        runCatching {
            val req = Request.Builder().url(url).apply { if (head) head() }.build()
            http.newCall(req).execute().use { r -> Probe(true, SystemClock.elapsedRealtime() - t0, "HTTP ${r.code}") }
        }.getOrElse { e -> Probe(false, null, e.javaClass.simpleName.removeSuffix("Exception").ifEmpty { "Error" }) }
    }

    /** Two well-known endpoints; either answering means the internet works. */
    suspend fun internet(): Probe {
        val a = timed("https://connectivitycheck.gstatic.com/generate_204")
        return if (a.ok) a else timed("https://cloudflare.com/cdn-cgi/trace").let { if (it.ok) it else a }
    }

    suspend fun diagnose(profile: Profile?, xtream: XtreamClient?, streamMbps: Float?): Diagnosis = coroutineScope {
        val net = async { internet() }
        var account: AccountInfo? = null
        val prov = async {
            when {
                xtream != null -> {
                    val t0 = SystemClock.elapsedRealtime()
                    runCatching { account = xtream.authenticate(); Probe(true, SystemClock.elapsedRealtime() - t0) }
                        .getOrElse { e -> Probe(false, null, e.message ?: e.javaClass.simpleName) }
                }
                profile?.type == SourceType.M3U -> timed(profile.m3uUrl.let { if (it.contains("://")) it else "http://$it" }, head = true)
                else -> Probe(false, null, "No provider")
            }
        }
        val i = net.await()
        val p = prov.await()
        val a = account
        when {
            !i.ok -> Diagnosis(i, p, Verdict.NO_INTERNET, "No internet connection",
                "This device can't reach the internet. Check the Wi-Fi or network cable, or restart your router.")
            !p.ok -> Diagnosis(i, p, Verdict.PROVIDER_DOWN, "Your provider's server isn't responding",
                "Your internet is fine (${i.ms} ms), but the provider's server didn't answer. It's on their end. Try again in a few minutes.")
            a != null && a.status != null && !a.status.equals("Active", ignoreCase = true) -> Diagnosis(i, p, Verdict.ACCOUNT,
                "Your account is ${a.status}", "The provider reports your subscription as \"${a.status}\". Check with them.")
            a != null && a.maxConnections != null && a.activeConnections != null &&
                (a.activeConnections.toIntOrNull() ?: 0) > (a.maxConnections.toIntOrNull() ?: Int.MAX_VALUE) -> Diagnosis(i, p, Verdict.ACCOUNT,
                "Too many connections", "${a.activeConnections} of ${a.maxConnections} connections in use. Close Tuned (or other apps) on other devices.")
            streamMbps != null -> Diagnosis(i, p, Verdict.SLOW_STREAM, "This stream is arriving too slowly",
                "Internet (${i.ms} ms) and provider (${p.ms} ms) both respond, but only %.1f Mbps is arriving for this channel. ".format(streamMbps) +
                    "The channel's source may be overloaded: try another channel, or Settings → Buffer size → Large.")
            else -> Diagnosis(i, p, Verdict.ALL_GOOD, "Connection looks good",
                "Internet ${i.ms} ms · provider ${p.ms} ms" + (a?.let { " · account ${it.status ?: "OK"}" } ?: ""))
        }
    }
}
