package com.andrewf13.tuned.data.guide

/** A sport the guide can find. Words are matched as whole words, case-insensitive. */
data class SportDef(
    val id: String,
    val label: String,
    val emoji: String,
    val words: List<String>,
    /** Words in a sports channel's name that imply this sport when the programme itself doesn't say. */
    val channelHints: List<String> = emptyList(),
)

/** What the guide says about one programme. */
data class Classified(val sport: String?, val live: Boolean, val replay: Boolean, val event: Boolean)

/**
 * Recognises sport in TV guide listings, tuned on real XMLTV data (Foxtel, Sky NZ, Sky UK, TSN):
 *   "NRL - R12 Bulldogs v Storm", "LIVE: USPGA Tour - WWT Championship Round 3",
 *   "FIA Formula One World Championship / FIA Formula 1: Brazil Grand Prix" (desc "LIVE coverage of …"),
 *   "Rugby League World Cup / RLWC SF1: Kiwis v Tonga Ext Hls" (highlights), "Test Classic - 1991/92 AUS v IND".
 * Order matters: league before union, MotoGP before F1 (both have "Grand Prix").
 */
object SportsCatalog {
    val all = listOf(
        SportDef("cricket", "Cricket", "🏏", listOf("cricket", "test match", "wbbl", "bbl", "big bash", "ipl", "odi", "t20", "t20i", "sheffield shield", "the ashes", "ashes", "the hundred", "county championship", "marsh cup", "ranji trophy", "super smash", "plunket shield"), listOf("cricket", "willow")),
        SportDef("afl", "AFL", "🏉", listOf("afl", "aflw", "australian football", "australian rules", "footy"), listOf("footy")),
        SportDef("nrl", "NRL & rugby league", "🏉", listOf("nrl", "nrlw", "rugby league", "rlwc", "state of origin", "betfred super league", "rfl", "kiwi ferns", "kangaroos", "jillaroos"), listOf("league")),
        SportDef("rugby", "Rugby union", "🏉", listOf("rugby union", "super rugby", "all blacks", "black ferns", "wallabies", "wallaroos", "springboks", "six nations", "rugby championship", "nations championship", "bledisloe", "premiership rugby", "top 14", "npc", "rugby world cup", "rugby"), listOf("rugby")),
        SportDef("motogp", "MotoGP", "🏍️", listOf("motogp", "moto2", "moto3", "motoe", "superbike", "worldsbk")),
        SportDef("f1", "Formula 1", "🏎️", listOf("formula 1", "formula one", "f1", "grand prix", "f2", "f3", "f1 academy"), listOf("f1")),
        SportDef("wrc", "Rally (WRC)", "🚙", listOf("wrc", "world rally", "rally", "rallye", "dakar")),
        SportDef("supercars", "Supercars & motorsport", "🏁", listOf("supercars", "bathurst", "sandown 500", "indycar", "nascar", "le mans", "wec", "motorsport", "v8")),
        SportDef("nfl", "NFL", "🏈", listOf("nfl", "american football", "super bowl", "college football", "night football", "redzone"), listOf("nfl")),
        SportDef("soccer", "Football (soccer)", "⚽", listOf("soccer", "a-league", "a league", "alm", "alw", "premier league", "champions league", "europa league", "conference league", "fa cup", "efl", "la liga", "serie a", "bundesliga", "ligue 1", "mls", "socceroos", "matildas", "fifa", "uefa", "women's super league", "wsl", "concacaf", "copa"), listOf("football", "premier league")),
        SportDef("tennis", "Tennis", "🎾", listOf("tennis", "atp", "wta", "wimbledon", "australian open", "roland garros", "us open tennis", "davis cup", "billie jean king cup", "united cup"), listOf("tennis")),
        SportDef("golf", "Golf", "⛳", listOf("golf", "pga", "uspga", "lpga", "uslpga", "liv golf", "dp world tour", "the masters", "open championship", "ryder cup", "presidents cup"), listOf("golf")),
        SportDef("basketball", "Basketball", "🏀", listOf("nba", "wnba", "nbl", "wnbl", "basketball", "euroleague", "ncaa basketball")),
        SportDef("icehockey", "Ice hockey", "🏒", listOf("nhl", "ice hockey", "hockey night"), listOf("hockey")),
        SportDef("combat", "Boxing & UFC", "🥊", listOf("ufc", "boxing", "mma", "fight night", "pfl", "bellator")),
        SportDef("baseball", "Baseball", "⚾", listOf("mlb", "baseball", "world series")),
        SportDef("cycling", "Cycling", "🚴", listOf("cycling", "tour de france", "giro d'italia", "vuelta", "tour down under")),
        SportDef("netball", "Netball", "🏐", listOf("netball", "super netball", "anz premiership")),
        SportDef("racing", "Horse racing", "🐎", listOf("horse racing", "thoroughbred", "melbourne cup", "harness racing", "greyhound", "trackside"), listOf("racing", "trackside", "thoroughbred")),
        SportDef("darts", "Darts & snooker", "🎯", listOf("darts", "pdc", "snooker")),
    )
    fun byId(id: String) = all.firstOrNull { it.id == id }

    private fun wordRegex(words: List<String>) =
        Regex("(?<![\\p{L}\\p{N}])(" + words.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    private val patterns = all.map { it to wordRegex(it.words) }
    private val hints = all.filter { it.channelHints.isNotEmpty() }.map { it to wordRegex(it.channelHints) }

    private val liveRx = Regex("""(^|\W)live(\s*:|\s+coverage|\s*\)|\s*-|\s+from|\s+on\b|$)|\(live\)""", RegexOption.IGNORE_CASE)
    private val replayRx = Regex("""(?<![\p{L}])(hls|highlights?|replay|replays|classic|classics|mini|wrap|review|greatest|encore|rpt|repeat|best of|rewind|plays of|catch-?up|delayed)(?![\p{L}])|\(r\)""", RegexOption.IGNORE_CASE)
    private val eventRx = Regex(
        """(?<![\p{L}\p{N}])(v|vs|v\.|vs\.|versus|grand prix|qualifying|practice|sprint|race|round \d+|r\d+|rnd ?\d+|final|finals|semi[- ]?final|quarter[- ]?final|day \d+|\d+(st|nd|rd|th) test|test match|stage \d+|rally|championship round|nhl|nfl|nba|mlb|ufc|wbbl|bbl|aflw|nrlw|ipl|odi|t20i?|elim final|preliminary final|grand final|game \d+|match \d+)(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE,
    )
    private val sportCategory = Regex("""sport|soccer|hockey|football|cricket|rugby|golf|tennis|basketball|baseball|racing|motor|boxing|cycling""", RegexOption.IGNORE_CASE)

    fun classify(title: String, sub: String?, desc: String?, categories: List<String>, channelName: String, previouslyShown: Boolean = false, liveTag: Boolean = false): Classified {
        val head = listOfNotNull(title, sub).joinToString(" | ")
        val text = head + " | " + categories.joinToString(" ") + " | " + desc.orEmpty().take(160)
        var sport = patterns.firstOrNull { (_, rx) -> rx.containsMatchIn(head) }?.first?.id
            ?: patterns.firstOrNull { (_, rx) -> rx.containsMatchIn(text) }?.first?.id
        val sporty = categories.any { sportCategory.containsMatchIn(it) } || channelName.contains("sport", true)
        if (sport == null && sporty) sport = hints.firstOrNull { (_, rx) -> rx.containsMatchIn(channelName) }?.first?.id
        val live = liveTag || liveRx.containsMatchIn(title) || liveRx.containsMatchIn(sub.orEmpty()) ||
            desc.orEmpty().trimStart().startsWith("live", ignoreCase = true)
        val replay = !live && (previouslyShown || replayRx.containsMatchIn(head))
        val event = live || eventRx.containsMatchIn(head)
        return Classified(sport, live, replay, event)
    }
}
