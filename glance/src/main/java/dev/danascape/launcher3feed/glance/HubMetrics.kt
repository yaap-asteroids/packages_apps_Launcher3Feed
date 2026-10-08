package dev.danascape.launcher3feed.glance

import android.content.Context

/**
 * The Glanceable Hub's grid geometry.
 *
 * The hub's grid is responsive, not fixed: `ResponsiveLazyHorizontalGrid` divides the space it is
 * given into `gridSize.height` rows and sizes each cell from that, with
 *
 * ```
 * cellSize = (availableSpace - padding - cellSpacing * (numCells - 1)) / numCells
 * ```
 *
 * `calculateNumCellsHeight` returns 3 for any normal phone height, and `calculateNumCellsWidth`
 * returns 1 below 600dp wide, so a phone hub is a single column of three rows. A widget occupies
 * `spanY` of those rows.
 *
 * `Dimensions.CardHeightFull = 530.dp` belongs to the older fixed grid and is not what a current
 * build lays out with, so nothing here uses it.
 */
class HubMetrics private constructor(private val itemSpacingDp: Float) {

    /** Gap between stacked cards, `Dimensions.ItemSpacingCompact`. */
    fun itemSpacingPx(density: Float): Int = (itemSpacingDp * density).toInt()

    /**
     * Height for a widget spanning [spanY] rows of a grid [rows] tall, laid out in
     * [availableHeightPx] of vertical space.
     */
    fun widgetHeightPx(spanY: Int, rows: Int, availableHeightPx: Int, density: Float): Int {
        val spacing = itemSpacingPx(density)
        val safeRows = rows.coerceAtLeast(1)
        val cell = (availableHeightPx - spacing * (safeRows - 1)) / safeRows
        val spans = spanY.coerceIn(1, safeRows)
        return (spans * cell + (spans - 1) * spacing).coerceAtLeast(1)
    }

    /**
     * Pixel pitch of one hub row: a one-span widget's height plus the gap below it.
     *
     * This is the unit the hub's own resize anchors step by (`getPxOffsetForResize(1)` in
     * `ResizeableItemFrameViewModel`), so a drag that covers one pitch commits one span here too,
     * regardless of how many spans the widget being resized currently occupies.
     */
    fun rowPitchPx(rows: Int, availableHeightPx: Int, density: Float): Int =
        widgetHeightPx(1, rows, availableHeightPx, density) + itemSpacingPx(density)

    companion object {
        /**
         * Rows the hub uses on a phone, from `calculateNumCellsHeight`, which returns 3 for any
         * height at or above its compact threshold.
         */
        const val RESPONSIVE_ROWS = 3

        /** Rows of the older fixed grid, where `CommunalContentSize` spans are 2, 3 or 6. */
        const val FIXED_ROWS = 6

        /**
         * `Dimensions.ItemSpacingCompact`. The hub only uses the larger `ItemSpacing` (32dp) on
         * medium and expanded windows; a phone is always a compact window, so this is the one that
         * applies here, for both the gap the grid itself is arranged with and the padding
         * `gridContentPadding` gives it.
         */
        private const val ITEM_SPACING_DP = 16f

        /** `Dimensions.ToolbarHeight`, the edit-mode toolbar's own height. */
        const val TOOLBAR_HEIGHT_DP = 40f

        fun from(context: Context): HubMetrics = HubMetrics(ITEM_SPACING_DP)

        /**
         * Which span scale the widgets are on.
         *
         * The two grids number spans differently — responsive widgets span 1 to 3 rows, while the
         * fixed grid's `CommunalContentSize` uses 2, 3 or 6 out of six — and the flag that decides
         * is not readable from here. A span of 6 only exists on the fixed grid, and a full
         * responsive column sums to 3, so anything above that is the fixed scale.
         */
        fun rowsFor(spans: List<Int>): Int =
            if (spans.any { it > RESPONSIVE_ROWS } || spans.sum() > RESPONSIVE_ROWS) {
                FIXED_ROWS
            } else {
                RESPONSIVE_ROWS
            }
    }
}
