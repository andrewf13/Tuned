package com.andrewf13.tuned.data

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("tuned", Context.MODE_PRIVATE)

    var account: Account?
        get() {
            val server = sp.getString("server", null) ?: return null
            return Account(server, sp.getString("user", "")!!, sp.getString("pass", "")!!)
        }
        set(v) {
            val e = sp.edit()
            if (v == null) e.remove("server").remove("user").remove("pass")
            else e.putString("server", v.server).putString("user", v.username).putString("pass", v.password)
            e.apply()
        }

    var favourites: Set<Int>
        get() = sp.getStringSet("favs", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = sp.edit().putStringSet("favs", v.map { it.toString() }.toSet()).apply()

    var lastStreamId: Int
        get() = sp.getInt("lastStream", -1)
        set(v) = sp.edit().putInt("lastStream", v).apply()

    var lastCategory: String
        get() = sp.getString("lastCategory", "all")!!
        set(v) = sp.edit().putString("lastCategory", v).apply()

    var afrEnabled: Boolean
        get() = sp.getBoolean("afr", true)
        set(v) = sp.edit().putBoolean("afr", v).apply()

    /** "ts" or "m3u8" */
    var streamFormat: String
        get() = sp.getString("format", "ts")!!
        set(v) = sp.edit().putString("format", v).apply()

    var resumeOnStart: Boolean
        get() = sp.getBoolean("resume", true)
        set(v) = sp.edit().putBoolean("resume", v).apply()

    var sortAlpha: Boolean
        get() = sp.getBoolean("sortAlpha", false)
        set(v) = sp.edit().putBoolean("sortAlpha", v).apply()

    /** Group ids to show, or null for "all groups" (new provider groups then appear automatically). */
    var visibleGroups: Set<String>?
        get() = if (sp.getBoolean("groupFilterOn", false)) sp.getStringSet("visibleGroups", emptySet())!!.toSet() else null
        set(v) {
            val e = sp.edit()
            if (v == null) e.putBoolean("groupFilterOn", false).remove("visibleGroups")
            else e.putBoolean("groupFilterOn", true).putStringSet("visibleGroups", v)
            e.apply()
        }

    /** Most recent first; the first entry is usually the channel playing now. */
    var recents: List<Int>
        get() = sp.getString("recents", "")!!.split(',').mapNotNull { it.toIntOrNull() }
        set(v) = sp.edit().putString("recents", v.joinToString(",")).apply()

    var groupSortAlpha: Boolean
        get() = sp.getBoolean("groupSortAlpha", false)
        set(v) = sp.edit().putBoolean("groupSortAlpha", v).apply()

    /** Channels the user has unticked inside their groups. */
    var hiddenChannels: Set<Int>
        get() = sp.getStringSet("hiddenChannels", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = sp.edit().putStringSet("hiddenChannels", v.map { it.toString() }.toSet()).apply()

    var themeId: String
        get() = sp.getString("theme", "blue")!!
        set(v) = sp.edit().putString("theme", v).apply()

    var volumeBoostDb: Int
        get() = sp.getInt("boostDb", 6)
        set(v) = sp.edit().putInt("boostDb", v).apply()
}
