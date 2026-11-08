package com.andrewf13.tuned.data.guide

import com.andrewf13.tuned.data.Channel
import com.andrewf13.tuned.remote.ChannelMatcher
import java.io.InputStream

/** A free community guide from epgshare01.online, one file per country. */
data class GuideCountry(val code: String, val name: String, val files: List<String>)

object GuideSources {
    const val EPGSHARE = "https://epgshare01.online/epgshare01/"
    val countries = listOf(
        GuideCountry("AU", "Australia", listOf("epg_ripper_AU1.xml.gz")),
        GuideCountry("NZ", "New Zealand", listOf("epg_ripper_NZ1.xml.gz")),
        GuideCountry("UK", "United Kingdom", listOf("epg_ripper_UK1.xml.gz")),
        GuideCountry("CA", "Canada", listOf("epg_ripper_CA2.xml.gz")),
        GuideCountry("US", "United States", listOf("epg_ripper_US2.xml.gz", "epg_ripper_US_SPORTS1.xml.gz")),
        GuideCountry("IE", "Ireland", listOf("epg_ripper_IE1.xml.gz")),
        GuideCountry("ZA", "South Africa", listOf("epg_ripper_ZA1.xml.gz")),
        GuideCountry("IN", "India", listOf("epg_ripper_IN1.xml.gz")),
    )
    fun byCode(c: String) = countries.firstOrNull { it.code == c }
}

/**
 * Builds the TV guide from layered XMLTV sources:
 *  1. the provider's own guide (exact: its channels carry the guide id),
 *  2. epgshare01 country guides, matched to the provider's channels by name.
 * A channel uses the first source with listings for it. To stay small even with tens of
 * thousands of channels, the full guide is kept only for [kept] channels (ticked groups and
 * favourites); for everything else only sport is kept, for the Sports guide.
 */
class GuideLoader(
    private val channels: List<Channel>,
    private val kept: Set<Int>,
    private val nowMs: Long,
    private val daysAhead: Int = 8,
) {
    private val from = nowMs - 6 * 3_600_000L
    private val to = nowMs + daysAhead * 86_400_000L
    private val rows = ArrayList<GuideRow>()
    private val channelMap = HashMap<Int, String>()
    private val mapScore = HashMap<Int, Int>()
    private val keysWithData = HashSet<String>()
    private val sources = ArrayList<String>()
    private val index by lazy { ChannelMatcher.Index(channels) }
    private val byId by lazy { channels.associateBy { it.streamId } }

    private fun add(key: String, chName: String, streams: Collection<Int>, p: XmltvProgramme) {
        if (p.stopMs <= from || p.startMs >= to) return
        val c = SportsCatalog.classify(p.title, p.sub, p.desc, p.categories, chName, p.previouslyShown, p.live)
        val isKept = streams.any { it in kept }
        if (!isKept && c.sport == null) return
        rows += GuideRow(key, p.startMs, p.stopMs, p.title, p.sub, if (isKept || c.sport != null) p.desc else null, c.sport, c.live, c.replay, c.event)
        keysWithData += key
    }

    /** The provider's guide: programmes are tied to channels by the provider's epg id. */
    fun addProvider(input: InputStream, label: String = "Provider guide") {
        val byEpg = channels.filter { !it.epgId.isNullOrBlank() }.groupBy({ it.epgId!! }, { it.streamId })
        val names = HashMap<String, String>()
        var n = 0
        XmltvReader.read(input, { id, dn -> names[id] = dn.firstOrNull() ?: id }) { p ->
            val streams = byEpg[p.channel] ?: return@read
            val before = rows.size
            add("p:${p.channel}", names[p.channel] ?: byId[streams.first()]?.name.orEmpty(), streams, p)
            if (rows.size > before) n++
        }
        // Provider listings win: map every channel whose guide id produced listings.
        for ((epg, streams) in byEpg) if ("p:$epg" in keysWithData) streams.forEach { channelMap[it] = "p:$epg"; mapScore[it] = Int.MAX_VALUE }
        if (n > 0) sources += label
    }

    /** A community guide for one country: its channels are matched to the provider's by name. */
    fun addCountryGuide(input: InputStream, country: GuideCountry, file: String) {
        val assigned = HashMap<String, List<Int>>() // guide channel → provider streams it fills in for
        val names = HashMap<String, String>()
        var n = 0
        XmltvReader.read(input, { id, dn ->
            val name = dn.firstOrNull() ?: return@read
            names[id] = name
            val key = "e:$id"
            val streams = ArrayList<Int>()
            for (m in index.match(name, country.name, emptySet(), limit = 50, minScore = 80)) {
                val s = m.channel.streamId
                if ((mapScore[s] ?: Int.MIN_VALUE) < m.score) { channelMap[s] = key; mapScore[s] = m.score; streams += s }
            }
            if (streams.isNotEmpty()) assigned[id] = streams
        }) { p ->
            val streams = assigned[p.channel] ?: return@read
            val before = rows.size
            add("e:${p.channel}", names[p.channel].orEmpty(), streams, p)
            if (rows.size > before) n++
        }
        if (n > 0) sources += "epgshare01 ${country.code}" + if (country.files.size > 1) " (${file.substringAfter("epg_ripper_").substringBefore(".xml")})" else ""
    }

    fun build(): GuideBuild {
        // A channel claimed by a guide channel that had no listings in range falls back to nothing.
        val map = channelMap.filterValues { it in keysWithData }
        val keptTotal = kept.count { it in byId }
        return GuideBuild(rows, map, keptTotal, kept.count { it in map }, sources.distinct())
    }
}
