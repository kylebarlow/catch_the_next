package dev.catchthenext.wear.tile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * ProtoLayout builders are plain Java, so the static half of the countdown prop is checkable on
 * the JVM. The dynamic half is only evaluated by an on-device renderer.
 */
class DynamicLabelsTest {

    @Test
    fun `the static value matches the timeLabel for the given minutes`() {
        val prop = countdownStringProp(System.currentTimeMillis() + 5 * 60_000, staticMinutes = 5L)
        assertEquals("5m", prop.value)
        assertNotNull(prop.dynamicValue, "Renderers on schema 1.2+ use the dynamic value")
    }

    @Test
    fun `zero or negative minutes fall back to Now`() {
        assertEquals("Now", countdownStringProp(System.currentTimeMillis(), staticMinutes = 0L).value)
        assertEquals("Now", countdownStringProp(System.currentTimeMillis() - 60_000, staticMinutes = -1L).value)
    }

    @Test
    fun `a layout constraint is supplied for the dynamic text`() {
        // A dynamic StringProp needs one, or the renderer cannot measure the row.
        assertNotNull(COUNTDOWN_LAYOUT_CONSTRAINT)
    }
}
