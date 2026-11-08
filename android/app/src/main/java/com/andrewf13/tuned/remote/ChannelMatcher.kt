package com.andrewf13.tuned.remote

import com.andrewf13.tuned.data.Channel

/**
 * Finds the provider channel that carries a broadcaster named in a sports TV listing,
 * e.g. "TSN 1" (Canada) → "CA| TSN1 FHD", "Sky Sports Main Event" (UK) → "UK: SKY SPORTS MAIN EVENT HD".
 *
 * Names are reduced to tokens (country tags, quality tags and punctuation removed; "tsn1" split
 * into "tsn" + "1"). Every token in the broadcaster's name must appear in the channel's name, and any
 * numbers must match exactly, so "Fox Sports 503" never matches "Fox Sports 505".
 */
object ChannelMatcher {
    data class Match(val channel: Channel, val score: Int)

    private val quality = setOf(
        "hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h265", "raw", "60fps", "50fps", "fps", "1080p", "720p", "2160p",
        "backup", "vip", "plus", "multi", "audio", "hq", "lq", "live", "tv", "channel", "ᴴᴰ", "ʰᵈ", "ᵁᴴᴰ", "ᶠᴴᴰ", "ʳᴬᵂ",
    )
    private val countryWords = mapOf(
        "australia" to "au", "new zealand" to "nz", "united kingdom" to "uk", "england" to "uk", "scotland" to "uk",
        "ireland" to "ie", "canada" to "ca", "united states" to "us", "usa" to "us", "south africa" to "za",
        "india" to "in", "france" to "fr", "germany" to "de", "spain" to "es", "italy" to "it", "portugal" to "pt",
    )
    private val countryCodes = countryWords.values.toSet() + setOf("gb", "nz", "au", "uk", "ca", "us")
    private val synonyms = mapOf("sports" to "sport", "one" to "1", "two" to "2", "three" to "3", "gb" to "uk")

    /** Country code of a listing's broadcaster ("Australia" → "au"), or null. */
    fun countryCode(country: String?): String? = country?.lowercase()?.trim()?.let { countryWords[it] ?: it.takeIf { c -> c.length == 2 } }

    /** The country tag a provider puts in front of a channel name ("AU| …", "UK: …", "[CA] …"), if any. */
    fun channelCountry(name: String): String? {
        val m = Regex("""^\s*[\[(]?([A-Za-z]{2,3})\s*[\])|:\-]""").find(name) ?: return null
        val c = m.groupValues[1].lowercase().let { synonyms[it] ?: it }
        return c.takeIf { it in countryCodes } // "4K| …" or "VIP| …" aren't countries
    }

    /** A country code written inside the name, e.g. "Sky Sport 1 NZ" → "nz". */
    fun countryToken(name: String): String? =
        name.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).map { synonyms[it] ?: it }.lastOrNull { it in setOf("au", "nz", "uk", "ca", "us", "ie", "za") }

    fun tokens(name: String, dropCountryTag: Boolean = true): List<String> {
        var s = name.lowercase()
        if (dropCountryTag) s = s.replace(Regex("""^\s*[\[(]?[a-z]{2,3}\s*[\])|:\-]+"""), " ")
        s = s.replace('&', ' ').replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        // quality tags with digits go before the letter/number split ("4k" would become "4" + "k")
        s = s.replace(Regex("""\b(4k|8k|\d{3,4}p|\d{2,3}fps|h26[45]|x26[45])\b"""), " ")
        // split letter/number boundaries: "tsn1" → "tsn 1", "fox503" → "fox 503"
        s = s.replace(Regex("""(?<=\p{L})(?=\p{N})|(?<=\p{N})(?=\p{L})"""), " ")
        return s.split(' ').filter { it.isNotBlank() }
            .map { synonyms[it] ?: it }
            .filter { it !in quality && !(it.length <= 3 && it in countryCodes && it != "1") }
    }

    /**
     * Best channels for [broadcaster], strongest first. [preferred] channels (favourites and the TV's
     * ticked list) win ties; a matching country tag helps.
     */
    fun match(broadcaster: String, country: String?, channels: List<Channel>, preferred: Set<Int>, limit: Int = 3): List<Match> {
        val want = tokens(broadcaster)
        if (want.isEmpty()) return emptyList()
        val wantNumbers = want.filter { it.all(Char::isDigit) }.toSet()
        val cc = countryCode(country)
        val out = ArrayList<Match>()
        for (c in channels) {
            val have = tokens(c.name)
            if (have.isEmpty()) continue
            val haveSet = have.toSet()
            if (!want.all { it in haveSet }) continue
            // Numbers in the channel name must be the broadcaster's numbers: "Sky Sport 1" ≠ "Sky Sport 1 Extra 2".
            val haveNumbers = have.filter { it.all(Char::isDigit) }.toSet()
            if (haveNumbers != wantNumbers) continue
            var score = 100 - 6 * (haveSet.size - want.toSet().size) // fewer extra words = closer
            val chCountry = channelCountry(c.name) ?: countryToken(c.name)
            if (cc != null && chCountry != null) {
                if (chCountry != cc) continue // e.g. US "Fox Sports 1" is not Australia's
                score += 25
            }
            if (c.streamId in preferred) score += 15
            if (c.name.contains("4k", true) || c.name.contains("uhd", true)) score += 1 // tie-break: the better picture
            out += Match(c, score)
        }
        return out.sortedByDescending { it.score }.take(limit)
    }
}
