package com.andrewf13.tuned.remote.sports

/** A sport you can follow. [db] is TheSportsDB's name for it; [hours] is how long an event usually runs. */
data class SportDef(val db: String, val label: String, val emoji: String, val hours: Double)

object Sports {
    val all = listOf(
        SportDef("Cricket", "Cricket", "🏏", 7.0),
        SportDef("Australian Football", "AFL", "🏉", 3.0),
        SportDef("Rugby", "Rugby (union & league)", "🏉", 2.0),
        SportDef("Motorsport", "Motorsport (F1, MotoGP, WRC…)", "🏁", 3.0),
        SportDef("American Football", "NFL", "🏈", 3.5),
        SportDef("Soccer", "Football (soccer)", "⚽", 2.0),
        SportDef("Basketball", "Basketball", "🏀", 2.5),
        SportDef("Tennis", "Tennis", "🎾", 3.0),
        SportDef("Golf", "Golf", "⛳", 5.0),
        SportDef("Ice Hockey", "Ice hockey", "🏒", 3.0),
        SportDef("Fighting", "Boxing & UFC", "🥊", 4.0),
        SportDef("Cycling", "Cycling", "🚴", 4.0),
        SportDef("Baseball", "Baseball", "⚾", 3.0),
        SportDef("Netball", "Netball", "🏐", 1.5),
        SportDef("Darts", "Darts", "🎯", 3.0),
        SportDef("Snooker", "Snooker", "🎱", 4.0),
    )
    fun byDb(name: String) = all.firstOrNull { it.db.equals(name, ignoreCase = true) }

    /** Countries whose broadcasters appear in the listings. */
    val countries = listOf("Australia", "New Zealand", "United Kingdom", "Canada", "United States", "Ireland", "South Africa", "India")
    val defaultCountries = setOf("Australia", "New Zealand", "United Kingdom", "Canada", "United States")
    val defaultSports = setOf("Cricket", "Australian Football", "Motorsport", "Rugby", "American Football")
    val defaultKeywords = listOf("Formula 1", "MotoGP", "WRC", "Super Rugby", "All Blacks")
}

/** A channel showing an event, from the listings. */
data class Broadcast(val channel: String, val country: String, val logo: String?) {
    /** Key used for matching and remembering the user's own choice. */
    val key: String get() = "$channel|$country"
}

/** One event with every channel showing it. */
data class SportEvent(
    val id: String,
    val title: String,
    val sport: String,
    val startMs: Long,
    val endMs: Long,
    val thumb: String?,
    val broadcasts: List<Broadcast>,
    val followed: Boolean,
) {
    fun isLive(now: Long) = now in startMs until endMs
}

object EventBuilder {
    /**
     * Turns listing rows into events: one per event, with its channels limited to the chosen
     * countries, dropping events that have finished. Keywords mark events as followed.
     */
    fun build(rows: List<Listing>, countries: Set<String>, keywords: List<String>, now: Long, horizonMs: Long = 7 * 86_400_000L): List<SportEvent> {
        val kw = keywords.map { it.trim() }.filter { it.isNotEmpty() }
        return rows.groupBy { it.eventId }.mapNotNull { (id, list) ->
            val first = list.first()
            val hours = Sports.byDb(first.sport)?.hours ?: 2.5
            val end = first.startMs + (hours * 3_600_000).toLong()
            if (end <= now || first.startMs > now + horizonMs) return@mapNotNull null
            val bc = list.filter { countries.isEmpty() || it.channelCountry in countries }
                .map { Broadcast(it.channel, it.channelCountry, it.channelLogo) }
                .distinctBy { it.key }
                .sortedWith(compareBy({ Sports.countries.indexOf(it.country).let { i -> if (i < 0) 99 else i } }, { it.channel }))
            if (bc.isEmpty()) return@mapNotNull null
            SportEvent(
                id, first.event, first.sport, first.startMs, end, list.firstNotNullOfOrNull { it.thumb }, bc,
                followed = kw.any { first.event.contains(it, ignoreCase = true) },
            )
        }.sortedWith(compareBy({ it.startMs }, { it.title }))
    }
}
