package net.hotiana.companion

/**
 * Where one item sits on Home.
 *
 * [col] is the first column the item occupies; how many it occupies is still its span, which
 * is derived from the item rather than stored here -- two stores for one fact is how they come
 * to disagree. [page] is always 0 today because Home is a single page; the field exists so that
 * adding pages later is not a stored-format change on a board people have already arranged.
 */
internal data class HomeCell(val page: Int, val col: Int, val row: Int)

/**
 * Flows items into a grid exactly the way LazyVerticalGrid does.
 *
 * Turns a derived layout into stored positions, as a visual no-op: the board someone
 * arranged must look identical the moment positions become stored rather than derived. That
 * only holds if this reproduces the real packing rule -- an
 * item that does not fit in what is left of a line moves to the next line and LEAVES the gap,
 * it does not get backfilled by a later, narrower item. Get that wrong and everything shifts
 * up by one somewhere below the first wide widget.
 *
 * Spans arrive as a function rather than a stored number for the reason above: the caller
 * computes them from the same expression the grid itself renders with.
 */
internal fun flowIntoCells(ids: List<String>, columns: Int, span: (String) -> Int): Map<String, HomeCell> {
    if (columns < 1) return emptyMap()
    val placed = LinkedHashMap<String, HomeCell>()
    var col = 0
    var row = 0
    ids.forEach { id ->
        val width = span(id).coerceIn(1, columns)
        if (col + width > columns) { row++; col = 0 }
        placed[id] = HomeCell(0, col, row)
        col += width
        if (col >= columns) { row++; col = 0 }
    }
    return placed
}

/** Reconcile footprints without repacking the gaps someone deliberately left on Home. */
internal fun reconcileHomeCells(ids: List<String>, columns: Int, span: (String) -> Int,
    previous: Map<String, HomeCell>, moving: Pair<String, HomeCell>? = null): Map<String, HomeCell> {
    if (columns < 1) return emptyMap()
    val placed = linkedMapOf<String, HomeCell>()
    val occupied = mutableSetOf<Pair<Int, Int>>()
    fun place(id: String, requested: HomeCell) {
        val width = span(id).coerceIn(1, columns)
        var row = requested.row.coerceAtLeast(0)
        var col = requested.col.coerceIn(0, columns - width)
        while ((col until col + width).any { row to it in occupied }) {
            col++
            if (col + width > columns) { row++; col = 0 }
        }
        placed[id] = HomeCell(0, col, row)
        (col until col + width).forEach { occupied += row to it }
    }
    moving?.takeIf { it.first in ids }?.let { place(it.first, it.second) }
    // Existing items reserve their positions first. A collision pushes forward through the
    // row, so neighbors make room in reading order instead of teleporting into the source.
    ids.filter { it in previous && it != moving?.first }
        .sortedWith(compareBy({ previous.getValue(it).row }, { previous.getValue(it).col }))
        .forEach { place(it, previous.getValue(it)) }
    ids.filter { it !in placed }.forEach { place(it, HomeCell(0, 0, 0)) }
    return placed
}

internal data class HomeSlot(val id: String?, val span: Int, val key: String, val cell: HomeCell)

/**
 * Turns stored cells into the flat list a LazyVerticalGrid can render, gaps included.
 *
 * A grid packs; it has no way to leave a hole. So the holes are rendered as real items that
 * happen to draw nothing -- that is what makes an icon able to sit in the third column with
 * nothing beside it, which is the entire point of free placement.
 *
 * The walk always advances by at least one column. A span that somehow arrived as zero would
 * otherwise spin here forever, and this runs on the home screen, where a hang would leave the device unusable.
 */
internal fun homeSlots(cells: Map<String, HomeCell>, columns: Int, span: (String) -> Int, minimumRows: Int = 0): List<HomeSlot> {
    if (columns < 1 || cells.isEmpty()) return emptyList()
    val starts = cells.entries.associate { (id, at) -> at.row to at.col to id }
    val lastRow = maxOf(cells.values.maxOf { it.row }, minimumRows.coerceIn(0, 24) - 1)
    val slots = mutableListOf<HomeSlot>()
    for (row in 0..lastRow) {
        var col = 0
        while (col < columns) {
            val id = starts[row to col]
            val width = if (id == null) 1 else span(id).coerceIn(1, columns - col)
            val at = HomeCell(0, col, row)
            slots += if (id == null) HomeSlot(null, 1, "gap:$row:$col", at) else HomeSlot(id, width, id, at)
            col += maxOf(1, width)
        }
    }
    // Home requests visible blank drop targets; callers describing stored placement can omit them.
    return if (minimumRows > 0) slots else slots.dropLastWhile { it.id == null }
}
