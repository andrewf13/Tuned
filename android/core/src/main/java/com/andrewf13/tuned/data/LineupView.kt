package com.andrewf13.tuned.data

/**
 * The channel list as the user sees it: their ticked groups (null = all), minus unticked
 * channels, in provider or A–Z order. Pure and immutable; rebuild when anything changes.
 */
class LineupView(
    val channels: List<Channel>,
    rawCategories: List<Category>,
    groupFilter: Set<String>?,
    hidden: Set<Int>,
    sortAlpha: Boolean,
    groupSortAlpha: Boolean,
) {
    private val alpha = sortAlpha
    val byId: Map<Int, Channel> = channels.associateBy { it.streamId }
    private val grouped = channels.groupBy { it.categoryId }

    /** Every provider group that has channels. */
    val allCategories: List<Category> = rawCategories.filter { grouped[it.id]?.isNotEmpty() == true }
        .let { if (groupSortAlpha) it.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { c -> c.name.trim() }) else it }

    private val shown = if (hidden.isEmpty()) grouped else grouped.mapValues { (_, l) -> l.filter { it.streamId !in hidden } }

    /** Groups in the list: ticked, with at least one ticked channel. */
    val categories: List<Category> = allCategories.filter { (groupFilter == null || it.id in groupFilter) && shown[it.id].orEmpty().isNotEmpty() }

    private val byCategory: Map<String, List<Channel>> = categories.associate { it.id to sorted(shown[it.id].orEmpty()) }

    /** Every channel in the list. */
    val all: List<Channel> = categories.flatMap { byCategory[it.id].orEmpty() }.let { if (alpha) sorted(it) else channels.filter { c -> c.categoryId in byCategory && c.streamId !in hidden } }

    fun sorted(list: List<Channel>) = if (alpha) list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) else list

    fun group(id: String): List<Channel> = byCategory[id].orEmpty()

    fun favourites(ids: Set<Int>): List<Channel> = sorted(channels.filter { it.streamId in ids })

    /** Search covers every channel the provider has, hidden or not. */
    fun search(q: String): List<Channel> {
        val t = q.trim()
        return if (t.length < 2) emptyList() else sorted(channels.filter { it.name.contains(t, ignoreCase = true) })
    }

    fun groupName(id: String?): String? = allCategories.firstOrNull { it.id == id }?.name

    companion object {
        val EMPTY = LineupView(emptyList(), emptyList(), null, emptySet(), false, false)
    }
}
