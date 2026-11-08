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
            if (v == null) e.clear() else e.putString("name", v.name).putString("host", v.host).putInt("port", v.port).putString("token", v.token)
            e.apply()
        }

    var lastFilter: String
        get() = sp.getString("filter", "fav")!!
        set(v) = sp.edit().putString("filter", v).apply()
}
