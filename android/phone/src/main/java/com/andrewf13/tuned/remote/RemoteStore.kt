package com.andrewf13.tuned.remote

import android.content.Context

data class SavedTv(val name: String, val host: String, val port: Int, val token: String)

/** The paired TV, remembered between launches. */
class RemoteStore(context: Context) {
    private val sp = context.getSharedPreferences("tuned_remote", Context.MODE_PRIVATE)

    var tv: SavedTv?
        get() {
            val token = sp.getString("token", null) ?: return null
            return SavedTv(sp.getString("name", "TV")!!, sp.getString("host", "")!!, sp.getInt("port", 8642), token)
        }
        set(v) {
            val e = sp.edit()
            if (v == null) e.remove("name").remove("host").remove("port").remove("token").remove("widgetChannels").remove("nowPlaying") else e.putString("name", v.name).putString("host", v.host).putInt("port", v.port).putString("token", v.token)
            e.apply()
        }

    /** "system", "light" or "dark". */
    var themeMode: String
        get() = sp.getString("theme", "system")!!
        set(v) = sp.edit().putString("theme", v).apply()

    /** Favourites as last seen, so the home-screen widget works without opening the app. */
    var widgetChannels: List<Pair<Int, String>>
        get() = sp.getString("widgetChannels", "")!!.split('\n').mapNotNull { line ->
            val id = line.substringBefore('\t').toIntOrNull() ?: return@mapNotNull null
            id to line.substringAfter('\t')
        }
        set(v) = sp.edit().putString("widgetChannels", v.joinToString("\n") { "${it.first}\t${it.second.replace('\n', ' ')}" }).apply()

    /** Name of the channel on the TV, as last seen. */
    var nowPlaying: String?
        get() = sp.getString("nowPlaying", null)
        set(v) = sp.edit().putString("nowPlaying", v).apply()

    // ---- Sports guide ----
    /** TheSportsDB key ("123" is the free test key: one listing per request). */
    var sportsKey: String
        get() = sp.getString("sportsKey", "")!!
        set(v) = sp.edit().putString("sportsKey", v.trim()).apply()
    var sports: Set<String>
        get() = sp.getStringSet("sports", com.andrewf13.tuned.remote.sports.Sports.defaultSports)!!.toSet()
        set(v) = sp.edit().putStringSet("sports", v).apply()
    var sportsCountries: Set<String>
        get() = sp.getStringSet("sportsCountries", com.andrewf13.tuned.remote.sports.Sports.defaultCountries)!!.toSet()
        set(v) = sp.edit().putStringSet("sportsCountries", v).apply()
    var sportsKeywords: List<String>
        get() = sp.getString("sportsKeywords", null)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: com.andrewf13.tuned.remote.sports.Sports.defaultKeywords
        set(v) = sp.edit().putString("sportsKeywords", v.joinToString(",")).apply()
    /** Channels you picked yourself for a broadcaster: "Fox Cricket|Australia" → channel id on the TV. */
    var channelPicks: Map<String, Int>
        get() = sp.getString("channelPicks", "")!!.split('\n').mapNotNull { l ->
            val id = l.substringAfterLast('\t').toIntOrNull() ?: return@mapNotNull null
            l.substringBeforeLast('\t') to id
        }.toMap()
        set(v) = sp.edit().putString("channelPicks", v.entries.joinToString("\n") { "${it.key}\t${it.value}" }).apply()

    var lastFilter: String
        get() = sp.getString("filter", "fav")!!
        set(v) = sp.edit().putString("filter", v).apply()
}
