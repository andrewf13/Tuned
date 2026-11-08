package com.andrewf13.tuned.remote.sports

import com.andrewf13.tuned.remote.TvChannel

/** A sport the TV guide recognises (the list comes from the TV). */
data class SportItem(val id: String, val label: String, val emoji: String)

/** A match from the TV guide, with every channel showing it. */
data class SportEvent(
    val title: String,
    val sub: String?,
    val sport: String?,
    val startMs: Long,
    val endMs: Long,
    val live: Boolean,
    val replay: Boolean,
    val followed: Boolean,
    val channels: List<TvChannel>,
) {
    val id: String get() = "$title|$sub|$startMs"
    fun isOn(now: Long) = now in startMs until endMs
}

object Sports {
    /** Used until the TV has sent its own list. */
    val fallback = listOf(
        SportItem("cricket", "Cricket", "🏏"), SportItem("afl", "AFL", "🏉"), SportItem("nrl", "NRL & rugby league", "🏉"),
        SportItem("rugby", "Rugby union", "🏉"), SportItem("f1", "Formula 1", "🏎️"), SportItem("motogp", "MotoGP", "🏍️"),
        SportItem("wrc", "Rally (WRC)", "🚙"), SportItem("nfl", "NFL", "🏈"),
    )
    val defaultSports = setOf("cricket", "afl", "nrl", "rugby", "f1", "motogp", "wrc", "nfl")
}
