package com.andrewf13.tuned.data

/**
 * Parses M3U / M3U8 playlists (#EXTINF lines with tvg-* and group-title attributes).
 * Movie and series entries that Xtream-style playlists mix in are skipped: this is a live TV player.
 */
object M3uParse {
    private val attr = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    fun parse(text: String): Lineup {
        val cats = LinkedHashMap<String, Category>()
        val channels = ArrayList<Channel>()
        var info: String? = null
        var extGroup: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXTINF", ignoreCase = true) -> { info = line; extGroup = null }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = line.substringAfter(':').trim()
                line.startsWith("#") -> {}
                info != null -> {
                    val url = line
                    val i = info!!
                    info = null
                    if (url.contains("/movie/") || url.contains("/series/")) continue
                    val attrs = attr.findAll(i).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    // The title is after the first comma that follows the last attribute value.
                    val afterAttrs = i.lastIndexOf('"').let { q -> if (q >= 0) q else i.indexOf(':') }
                    val comma = i.indexOf(',', afterAttrs.coerceAtLeast(0))
                    val title = (if (comma >= 0) i.substring(comma + 1).trim() else "").ifEmpty { attrs["tvg-name"].orEmpty() }
                    val group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: extGroup ?: "Ungrouped"
                    cats.getOrPut(group) { Category(group, group) }
                    channels += Channel(
                        streamId = idFor(url),
                        num = attrs["tvg-chno"]?.toIntOrNull() ?: (channels.size + 1),
                        name = title.ifEmpty { "Channel ${channels.size + 1}" },
                        icon = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                        categoryId = group,
                        epgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                        url = url,
                    )
                }
            }
        }
        return Lineup(cats.values.toList(), channels.distinctBy { it.streamId })
    }

    /** A stable id from the stream URL, so favourites survive playlist reloads. */
    fun idFor(url: String): Int {
        val crc = java.util.zip.CRC32().apply { update(url.toByteArray()) }.value
        return ((crc and 0x7fffffff).toInt()).coerceAtLeast(1)
    }
}
