package com.andrewf13.tuned.data.guide

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.GZIPInputStream

data class XmltvProgramme(
    val channel: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val sub: String?,
    val desc: String?,
    val categories: List<String>,
    val previouslyShown: Boolean,
    val live: Boolean,
)

/**
 * Streams an XMLTV file (plain or .gz) without holding it in memory: channels first, then
 * programmes, handed over one at a time. Big provider guides can be hundreds of MB.
 */
object XmltvReader {
    fun read(input: InputStream, onChannel: (id: String, names: List<String>) -> Unit, onProgramme: (XmltvProgramme) -> Unit) {
        val buffered = BufferedInputStream(input, 64 * 1024)
        buffered.mark(2)
        val gz = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        val stream = if (gz) GZIPInputStream(buffered, 64 * 1024) else buffered
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(stream, null)

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (p.name) {
                    "channel" -> readChannel(p)?.let { (id, names) -> onChannel(id, names) }
                    "programme" -> readProgramme(p)?.let(onProgramme)
                }
            }
            event = p.next()
        }
    }

    private fun readChannel(p: XmlPullParser): Pair<String, List<String>>? {
        val id = p.getAttributeValue(null, "id") ?: return null
        val names = ArrayList<String>()
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "display-name") names += p.nextText().trim()
            if (p.eventType == XmlPullParser.END_DOCUMENT) break
        }
        return id to names
    }

    private fun readProgramme(p: XmlPullParser): XmltvProgramme? {
        val channel = p.getAttributeValue(null, "channel")
        val start = parseTime(p.getAttributeValue(null, "start"))
        val stop = parseTime(p.getAttributeValue(null, "stop"))
        var title: String? = null
        var sub: String? = null
        var desc: String? = null
        val cats = ArrayList<String>(2)
        var prev = false
        var live = false
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType == XmlPullParser.END_DOCUMENT) break
            if (p.eventType != XmlPullParser.START_TAG || p.depth != depth + 1) continue
            when (p.name) {
                "title" -> if (title == null) title = p.nextText().trim()
                "sub-title" -> if (sub == null) sub = p.nextText().trim().ifEmpty { null }
                "desc" -> if (desc == null) desc = p.nextText().trim().take(400).ifEmpty { null }
                "category" -> p.nextText().trim().takeIf { it.isNotEmpty() }?.let { cats += it }
                "previously-shown" -> prev = true
                "live" -> live = true
            }
        }
        if (channel == null || start == null || title.isNullOrEmpty()) return null
        return XmltvProgramme(channel, start, stop ?: (start + 3_600_000), title!!, sub, desc, cats, prev, live)
    }

    /** "20261108000000 +0000" → epoch ms. Offset optional (UTC assumed). */
    fun parseTime(s: String?): Long? {
        if (s == null || s.length < 12) return null
        return try {
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            c.clear()
            c.set(s.substring(0, 4).toInt(), s.substring(4, 6).toInt() - 1, s.substring(6, 8).toInt(),
                s.substring(8, 10).toInt(), s.substring(10, 12).toInt(), if (s.length >= 14 && s[12].isDigit()) s.substring(12, 14).toInt() else 0)
            var ms = c.timeInMillis
            val tz = s.substringAfter(' ', "").trim()
            if (tz.length == 5 && (tz[0] == '+' || tz[0] == '-')) {
                val mins = tz.substring(1, 3).toInt() * 60 + tz.substring(3, 5).toInt()
                ms -= (if (tz[0] == '+') 1 else -1) * mins * 60_000L
            }
            ms
        } catch (e: Exception) {
            null
        }
    }
}
