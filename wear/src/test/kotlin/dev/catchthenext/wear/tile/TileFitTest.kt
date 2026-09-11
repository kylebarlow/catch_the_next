package dev.catchthenext.wear.tile

import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import dev.catchthenext.android.tile.Tuning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

/**
 * [TileFit] is pure arithmetic over the device parameters, so the fit guarantees are checkable
 * on the JVM for every screen the app ships to. Screen sizes below are real devices:
 * 192dp Pixel Watch 1/2 (41mm), 198dp Galaxy Watch4 40mm, 227dp TicWatch E3, 240dp Galaxy
 * Watch6 Classic 47mm.
 */
class TileFitTest {

    private fun round(dp: Int, fontScale: Float = 1f) =
        TileFit(screenWidthDp = dp, screenHeightDp = dp, isRound = true, fontScale = fontScale)

    private fun square(w: Int, h: Int, fontScale: Float = 1f) =
        TileFit(screenWidthDp = w, screenHeightDp = h, isRound = false, fontScale = fontScale)

    private val screens = listOf(192, 198, 208, 227, 240)
    private val fontScales = listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f)

    /** Height the rendered content column will take for [groups] groups. */
    private fun contentHeight(fit: TileFit, groups: Int, hasHeader: Boolean): Float =
        (if (hasHeader) fit.headerHeightDp else 0f) +
            groups * fit.groupHeightDp + (groups - 1) * fit.groupSpacerDp

    @Test
    fun `budgeted groups always fit inside the content band`() {
        for (dp in screens) for (fs in fontScales) for (header in listOf(true, false)) {
            val fit = round(dp, fs)
            val n = fit.maxGroups(header)
            if (n > 1) {
                assertTrue(
                    contentHeight(fit, n, header) <= fit.contentHeightDp + 0.01f,
                    "$dp dp @ x$fs header=$header: $n groups overflow the band",
                )
            }
            // And the budget is not needlessly stingy: one more group would not have fit.
            if (n < Tuning.TILE_MAX_GROUPS) {
                assertTrue(
                    contentHeight(fit, n + 1, header) > fit.contentHeightDp,
                    "$dp dp @ x$fs header=$header: room for ${n + 1} groups but only $n budgeted",
                )
            }
        }
    }

    @Test
    fun `the footer never overlaps the content band on any screen`() {
        for (dp in screens) for (fs in fontScales) {
            val fit = round(dp, fs)
            val bandBottom = dp / 2f + fit.contentHeightDp / 2f
            val footerTop = dp - fit.footerBottomInsetDp - fit.footerLineDp
            assertTrue(footerTop - bandBottom >= TileFit.FOOTER_GAP_DP - 0.01f, "$dp dp @ x$fs: footer collides with content")
        }
        for (fs in fontScales) {
            val fit = square(180, 180, fs)
            val bandBottom = 90f + fit.contentHeightDp / 2f
            val footerTop = 180 - fit.footerBottomInsetDp - fit.footerLineDp
            assertTrue(footerTop - bandBottom >= TileFit.FOOTER_GAP_DP - 0.01f, "square @ x$fs: footer collides with content")
        }
    }

    @Test
    fun `the round band sits inside the circle`() {
        for (dp in screens) {
            val fit = round(dp)
            val r = dp / 2f
            // Corner of the band, measured from the centre, must be inside the radius.
            val cornerDistance = sqrt((fit.contentWidthDp / 2f).let { it * it } + (fit.contentHeightDp / 2f).let { it * it })
            assertTrue(cornerDistance < r, "$dp dp: band corner at $cornerDistance dp is outside r=$r")
        }
    }

    @Test
    fun `a large watch at default font scale shows the full three groups`() {
        assertEquals(3, round(240).maxGroups(hasHeader = true))
        assertEquals(3, round(240).maxGroups(hasHeader = false))
    }

    @Test
    fun `a small watch drops to two groups when the stop header is shown`() {
        val fit = round(192)
        assertEquals(2, fit.maxGroups(hasHeader = true))
        assertEquals(3, fit.maxGroups(hasHeader = false))
    }

    @Test
    fun `a large font scale reduces the group count rather than overflowing`() {
        assertTrue(round(240, fontScale = 1.3f).maxGroups(hasHeader = true) < 3)
        assertTrue(round(192, fontScale = 1.5f).maxGroups(hasHeader = true) <= 2)
    }

    @Test
    fun `at least one group is always rendered`() {
        assertEquals(1, round(120, fontScale = 2f).maxGroups(hasHeader = true))
        assertEquals(1, square(100, 100, fontScale = 2f).maxGroups(hasHeader = true))
    }

    @Test
    fun `route label keeps the route number and trims the headsign to the row`() {
        val fit = round(192)
        val label = fit.routeLabel("14", "Mission St & Daly City BART Station", hasAlert = false)
        assertTrue(label.startsWith("14 → "), label)
        assertTrue(label.endsWith("…"), label)
        assertTrue(label.length <= fit.routeLabelChars(hasAlert = false), "$label exceeds the row budget")
        // A short headsign is left alone.
        assertEquals("14 → Downtown", fit.routeLabel("14", "Downtown", hasAlert = false))
        // No headsign, no arrow.
        assertEquals("N", fit.routeLabel("N", "", hasAlert = false))
    }

    @Test
    fun `the alert icon takes characters away from the route label`() {
        val fit = round(192)
        assertTrue(fit.routeLabelChars(hasAlert = true) < fit.routeLabelChars(hasAlert = false))
    }

    @Test
    fun `a headsign that cannot get a few characters is dropped rather than mangled`() {
        val fit = round(120, fontScale = 2f)
        val label = fit.routeLabel("Caltrain", "San Francisco", hasAlert = true)
        assertEquals("Caltrain", label)
        assertFalse(label.contains("→"))
    }

    @Test
    fun `device parameters map onto the fit`() {
        val params: DeviceParameters = DeviceParameters.Builder()
            .setScreenWidthDp(240)
            .setScreenHeightDp(240)
            .setScreenShape(DeviceParametersBuilders.SCREEN_SHAPE_ROUND)
            .setFontScale(1.15f)
            .build()
        val fit = TileFit.of(params)
        assertEquals(TileFit(240, 240, isRound = true, fontScale = 1.15f), fit)

        val unspecified = DeviceParameters.Builder().setScreenWidthDp(192).setScreenHeightDp(192).build()
        assertTrue(TileFit.of(unspecified).isRound, "unspecified shape is treated as round")
        assertEquals(1f, TileFit.of(unspecified).fontScale.takeIf { it > 0f } ?: 1f)

        val rect = DeviceParameters.Builder()
            .setScreenWidthDp(180).setScreenHeightDp(180)
            .setScreenShape(DeviceParametersBuilders.SCREEN_SHAPE_RECT).build()
        assertFalse(TileFit.of(rect).isRound)
    }
}
