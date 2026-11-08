package com.andrewf13.tuned.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LineupViewTest {
    private val cats = listOf(Category("1", "AU | Sport"), Category("2", "NZ | Sport"), Category("3", "Empty"))
    private val ch = listOf(
        Channel(10, 1, "Fox Footy", null, "1", null), Channel(11, 2, "Fox Cricket", null, "1", null),
        Channel(20, 3, "Sky Sport 2", null, "2", null), Channel(21, 4, "Sky Sport 1", null, "2", null),
    )

    @Test fun ticksUntickedAndOrder() {
        val v = LineupView(ch, cats, setOf("1", "2"), hidden = setOf(10), sortAlpha = false, groupSortAlpha = false)
        assertEquals(listOf("AU | Sport", "NZ | Sport"), v.categories.map { it.name }) // "Empty" has no channels
        assertEquals(listOf(11), v.group("1").map { it.streamId })
        assertEquals(listOf(11, 20, 21), v.all.map { it.streamId })
        val az = LineupView(ch, cats, null, emptySet(), sortAlpha = true, groupSortAlpha = true)
        assertEquals(listOf("Sky Sport 1", "Sky Sport 2"), az.group("2").map { it.name })
        assertEquals(listOf("Fox Cricket", "Fox Footy", "Sky Sport 1", "Sky Sport 2"), az.all.map { it.name })
    }

    @Test fun hiddenGroupDropsOutButSearchStillFinds() {
        val v = LineupView(ch, cats, setOf("2"), emptySet(), false, false)
        assertEquals(listOf("NZ | Sport"), v.categories.map { it.name })
        assertEquals(listOf("Fox Footy"), v.search("footy").map { it.name })
        assertEquals(listOf("Fox Cricket"), v.favourites(setOf(11)).map { it.name })
    }
}
