package dev.catchthenext.wear.tile

import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import dev.catchthenext.android.tile.Tuning
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Per-device layout budget for the departures tile.
 *
 * ProtoLayout has no "fit to screen" primitive: a column that is taller than its slot is simply
 * clipped, and a text row wider than the screen is ellipsised at whatever point the bezel
 * happens to fall. So the tile decides *before* building the layout how much it can show, from
 * the only inputs that vary between watches — screen size, shape and the user's font scale —
 * and then renders exactly that much:
 *
 *  - a centred content band whose height and width are chosen so that every row is inside the
 *    round bezel (width is the chord of the circle at the band's top/bottom edge),
 *  - a footer slot below the band that always holds the "Updated h:mma" label, laid out
 *    independently of the content so nothing can push it off screen,
 *  - a group count derived from the band height and the line heights of the typographies used,
 *  - a character budget for the route label so the headsign is trimmed predictably instead of
 *    swallowing the end of the row.
 *
 * All numbers are dp. Text sizes are `sp × fontScale`, which is what the renderer draws.
 * Line heights come from protolayout-material 1.2.0's `Typography` table; re-check them when
 * that library is bumped (docs/wear-tile-client-plan.md §11).
 */
data class TileFit(
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val isRound: Boolean,
    val fontScale: Float,
) {
    private val fs = if (fontScale > 0f) fontScale else 1f

    /** Height of the "Updated" footer line. */
    val footerLineDp: Float = FOOTER_LINE_SP * fs

    /** Gap between the bottom of the footer text and the screen edge. */
    val footerBottomInsetDp: Float =
        if (isRound) ROUND_FOOTER_INSET_FRACTION * min(screenWidthDp, screenHeightDp) else SQUARE_FOOTER_INSET_DP

    /** Height of the band the departure rows are centred in. */
    val contentHeightDp: Float

    /** Width of the band; rows are start-aligned inside it. */
    val contentWidthDp: Float

    init {
        // The band may never reach into the footer slot, whatever the font scale.
        val footerReserve = footerBottomInsetDp + footerLineDp + FOOTER_GAP_DP
        if (isRound) {
            val d = min(screenWidthDp, screenHeightDp).toFloat()
            val r = d / 2f
            val halfBand = min(ROUND_BAND_HEIGHT_FRACTION * r, r - footerReserve).coerceAtLeast(0f)
            contentHeightDp = 2f * halfBand
            // Chord of the circle at the band's edge, minus a little for the physical bezel.
            val chord = 2f * sqrt((r * r - halfBand * halfBand).coerceAtLeast(0f))
            contentWidthDp = (chord - ROUND_BEZEL_INSET_FRACTION * d).coerceAtLeast(0f)
        } else {
            val halfBand = min(SQUARE_BAND_HEIGHT_FRACTION * screenHeightDp / 2f, screenHeightDp / 2f - footerReserve)
            contentHeightDp = (2f * halfBand).coerceAtLeast(0f)
            contentWidthDp = SQUARE_BAND_WIDTH_FRACTION * screenWidthDp
        }
    }

    /** Height of the single-stop header line (stop name) plus the gap under it. */
    val headerHeightDp: Float = HEADER_LINE_SP * fs + HEADER_GAP_DP

    /** Height of one departure group: route label line over times line. */
    val groupHeightDp: Float = (ROUTE_LINE_SP + TIMES_LINE_SP) * fs

    /** Vertical gap between consecutive groups. */
    val groupSpacerDp: Float = GROUP_SPACER_DP

    /**
     * How many two-line groups fit in the band, at most [Tuning.TILE_MAX_GROUPS]. Never less than
     * one: a single clipped group beats an empty tile if the font scale is extreme.
     */
    fun maxGroups(hasHeader: Boolean): Int {
        val available = contentHeightDp - (if (hasHeader) headerHeightDp else 0f)
        val n = floor((available + groupSpacerDp) / (groupHeightDp + groupSpacerDp)).toInt()
        return n.coerceIn(1, Tuning.TILE_MAX_GROUPS)
    }

    /** Characters of BODY2 text that fit on the route label row (minus the alert icon if shown). */
    fun routeLabelChars(hasAlert: Boolean): Int {
        val width = contentWidthDp - (if (hasAlert) ALERT_ICON_DP + ALERT_GAP_DP else 0f)
        return charsFor(width, ROUTE_SIZE_SP)
    }

    /** Characters of CAPTION1 text that fit on the header row. */
    val headerChars: Int get() = charsFor(contentWidthDp, HEADER_SIZE_SP)

    /**
     * Route label for a group: route number, then as much of the headsign as fits the row. The
     * headsign is dropped entirely when fewer than [MIN_HEADSIGN_CHARS] characters would remain,
     * since "14 → Mi…" tells the rider nothing.
     */
    fun routeLabel(routeShortName: String, headsign: String, hasAlert: Boolean): String {
        if (headsign.isBlank()) return routeShortName
        val budget = routeLabelChars(hasAlert) - routeShortName.length - HEADSIGN_SEPARATOR.length
        if (budget < MIN_HEADSIGN_CHARS) return routeShortName
        val trimmed = if (headsign.length <= budget) headsign else headsign.take(budget - 1).trimEnd() + "…"
        return routeShortName + HEADSIGN_SEPARATOR + trimmed
    }

    private fun charsFor(widthDp: Float, sizeSp: Float): Int =
        floor(widthDp / (sizeSp * fs * AVERAGE_GLYPH_EM)).toInt().coerceAtLeast(0)

    companion object {
        fun of(deviceParams: DeviceParameters): TileFit = TileFit(
            screenWidthDp = deviceParams.screenWidthDp,
            screenHeightDp = deviceParams.screenHeightDp,
            // Wear reports UNSPECIFIED on some renderers; every current watch is round.
            isRound = deviceParams.screenShape != DeviceParametersBuilders.SCREEN_SHAPE_RECT,
            fontScale = deviceParams.fontScale,
        )

        // protolayout-material 1.2.0 Typography: TYPOGRAPHY_BODY2 = 14sp / 18sp line,
        // CAPTION1 = 14 / 18, CAPTION2 = 12 / 16, CAPTION3 = 10 / 14.
        const val ROUTE_SIZE_SP = 14f
        const val ROUTE_LINE_SP = 18f
        const val TIMES_LINE_SP = 18f
        const val HEADER_SIZE_SP = 14f
        const val HEADER_LINE_SP = 18f
        const val FOOTER_LINE_SP = 14f

        /** Average advance of a Roboto glyph in em, for mixed-case route labels; digits are ~0.56. */
        const val AVERAGE_GLYPH_EM = 0.55f

        const val HEADSIGN_SEPARATOR = " → "
        const val MIN_HEADSIGN_CHARS = 4

        const val GROUP_SPACER_DP = 4f
        const val HEADER_GAP_DP = 2f
        const val FOOTER_GAP_DP = 4f
        const val ALERT_ICON_DP = 12f
        const val ALERT_GAP_DP = 4f

        /**
         * Round band: ±0.31·D tall, which puts its corners on the circle at a chord of 0.78·D —
         * the widest band that is still fully inside the bezel with start-aligned rows.
         */
        const val ROUND_BAND_HEIGHT_FRACTION = 0.62f
        const val ROUND_BEZEL_INSET_FRACTION = 0.03f
        const val ROUND_FOOTER_INSET_FRACTION = 0.04f

        const val SQUARE_BAND_HEIGHT_FRACTION = 0.80f
        const val SQUARE_BAND_WIDTH_FRACTION = 0.90f
        const val SQUARE_FOOTER_INSET_DP = 6f
    }
}
