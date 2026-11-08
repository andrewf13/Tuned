package com.andrewf13.tuned.data.guide

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.andrewf13.tuned.data.Programme

/** One stored listing. [key] identifies the guide channel ("p:<provider epg id>" or "e:<epgshare id>"). */
data class GuideRow(
    val key: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val sub: String?,
    val desc: String?,
    val sport: String?,
    val live: Boolean,
    val replay: Boolean,
    val event: Boolean,
)

data class GuideBuild(
    val rows: List<GuideRow>,
    /** Provider channel (stream id) → guide channel key. */
    val channelMap: Map<Int, String>,
    val keptTotal: Int,
    val keptCovered: Int,
    val sources: List<String>,
)

/** The TV guide on disk: one small SQLite database per provider profile. */
class GuideStore(context: Context, profileId: String) :
    SQLiteOpenHelper(context, "guide_$profileId.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE prog (k TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT NOT NULL, sub TEXT, descr TEXT, sport TEXT, flags INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX prog_k ON prog (k, start)")
        db.execSQL("CREATE INDEX prog_sport ON prog (sport, start)")
        db.execSQL("CREATE TABLE chan (stream INTEGER PRIMARY KEY, k TEXT NOT NULL)")
        db.execSQL("CREATE TABLE meta (name TEXT PRIMARY KEY, value TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Volatile private var channelMap: Map<Int, String>? = null
    @Volatile private var streamsByKey: Map<String, List<Int>>? = null

    private fun maps() {
        if (channelMap != null) return
        val m = HashMap<Int, String>()
        readableDatabase.rawQuery("SELECT stream, k FROM chan", null).use { c -> while (c.moveToNext()) m[c.getInt(0)] = c.getString(1) }
        channelMap = m
        streamsByKey = m.entries.groupBy({ it.value }, { it.key })
    }

    /** Replace everything with a freshly built guide, in one transaction. */
    @Synchronized
    fun replace(b: GuideBuild, nowMs: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("prog", null, null); db.delete("chan", null, null)
            val st = db.compileStatement("INSERT INTO prog (k, start, stop, title, sub, descr, sport, flags) VALUES (?,?,?,?,?,?,?,?)")
            for (r in b.rows) {
                st.clearBindings()
                st.bindString(1, r.key); st.bindLong(2, r.startMs); st.bindLong(3, r.stopMs); st.bindString(4, r.title)
                r.sub?.let { st.bindString(5, it) }; r.desc?.let { st.bindString(6, it) }; r.sport?.let { st.bindString(7, it) }
                st.bindLong(8, (if (r.live) 1L else 0) or (if (r.replay) 2L else 0) or (if (r.event) 4L else 0))
                st.executeInsert()
            }
            val cs = db.compileStatement("INSERT INTO chan (stream, k) VALUES (?,?)")
            for ((s, k) in b.channelMap) { cs.clearBindings(); cs.bindLong(1, s.toLong()); cs.bindString(2, k); cs.executeInsert() }
            fun meta(n: String, v: String) = db.insertWithOnConflict("meta", null, ContentValues().apply { put("name", n); put("value", v) }, SQLiteDatabase.CONFLICT_REPLACE)
            meta("updated", nowMs.toString()); meta("keptTotal", b.keptTotal.toString()); meta("keptCovered", b.keptCovered.toString())
            meta("programmes", b.rows.size.toString()); meta("sources", b.sources.joinToString(", "))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        channelMap = null; streamsByKey = null
    }

    fun meta(name: String): String? = runCatching {
        readableDatabase.rawQuery("SELECT value FROM meta WHERE name = ?", arrayOf(name)).use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    val updatedAt: Long get() = meta("updated")?.toLongOrNull() ?: 0

    fun hasGuide(streamId: Int): Boolean { maps(); return channelMap!!.containsKey(streamId) }

    /** What's on now and next for a channel (empty if the guide doesn't cover it). */
    fun upcoming(streamId: Int, nowMs: Long, limit: Int = 4): List<Programme> {
        maps()
        val k = channelMap!![streamId] ?: return emptyList()
        val out = ArrayList<Programme>()
        readableDatabase.rawQuery(
            "SELECT title, sub, descr, start, stop FROM prog WHERE k = ? AND stop > ? ORDER BY start LIMIT ?",
            arrayOf(k, nowMs.toString(), limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val title = c.getString(0)
                val sub = c.getString(1)
                out += Programme(if (sub != null && !title.contains(sub)) "$title: $sub" else title, c.getString(2).orEmpty(), c.getLong(3), c.getLong(4))
            }
        }
        return out
    }

    data class SportRow(val key: String, val row: GuideRow, val streams: List<Int>)

    /** Sports listings between [fromMs] and [toMs] for these sports (or matching these keywords). */
    fun sports(sports: Set<String>, keywords: List<String>, includeReplays: Boolean, fromMs: Long, toMs: Long): List<SportRow> {
        maps()
        val conds = ArrayList<String>()
        val args = ArrayList<String>()
        if (sports.isNotEmpty()) { conds += "sport IN (" + sports.joinToString(",") { "?" } + ")"; args += sports }
        keywords.filter { it.isNotBlank() }.take(20).forEach { conds += "(title LIKE ? OR sub LIKE ?)"; args += "%$it%"; args += "%$it%" }
        if (conds.isEmpty()) return emptyList()
        val where = StringBuilder("(" + conds.joinToString(" OR ") + ") AND stop > ? AND start < ? AND (flags & 5) != 0")
        args += fromMs.toString(); args += toMs.toString()
        if (!includeReplays) where.append(" AND (flags & 2) = 0")
        val out = ArrayList<SportRow>()
        readableDatabase.rawQuery("SELECT k, start, stop, title, sub, descr, sport, flags FROM prog WHERE $where ORDER BY start LIMIT 2000", args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val k = c.getString(0)
                val streams = streamsByKey!![k] ?: continue
                val f = c.getInt(7)
                out += SportRow(k, GuideRow(k, c.getLong(1), c.getLong(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), f and 1 != 0, f and 2 != 0, f and 4 != 0), streams)
            }
        }
        return out
    }
}
