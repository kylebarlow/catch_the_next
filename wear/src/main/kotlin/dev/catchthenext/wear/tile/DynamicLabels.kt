package dev.catchthenext.wear.tile

import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.TypeBuilders
import androidx.wear.protolayout.expression.DynamicBuilders
import dev.catchthenext.android.tile.timeLabel
import java.time.Instant

/**
 * Renderer-side countdown labels. A [TypeBuilders.StringProp] carrying a dynamic value is
 * re-evaluated by the tile renderer itself, so "5m" ticks down to "4m" without the system
 * calling `tileRequest` again. Renderers older than schema 1.2 ignore the dynamic value and
 * show the static one, which is why every prop here is built with a sensible static fallback —
 * see [supportsDynamicExpressions].
 */

/** Widest value the countdown label can take, for layout measurement. */
val COUNTDOWN_LAYOUT_CONSTRAINT: TypeBuilders.StringLayoutConstraint =
    TypeBuilders.StringLayoutConstraint.Builder("00m").build()

/**
 * "Now" when <= 0 minutes remain, else "<n>m" — the same semantics as [timeLabel] applied to
 * `(departure - now) / 60_000`, but recomputed by the renderer rather than frozen at build time.
 */
fun countdownStringProp(departureEpochMillis: Long, staticMinutes: Long): TypeBuilders.StringProp {
    val target = DynamicBuilders.DynamicInstant.withSecondsPrecision(Instant.ofEpochMilli(departureEpochMillis))
    val minutes = DynamicBuilders.DynamicInstant.platformTimeWithSecondsPrecision()
        .durationUntil(target)
        .toIntMinutes()
    val label = DynamicBuilders.DynamicString.onCondition(minutes.lte(0))
        .use("Now")
        .elseUse(minutes.format().concat(DynamicBuilders.DynamicString.constant("m")))
    return TypeBuilders.StringProp.Builder(timeLabel(staticMinutes))
        .setDynamicValue(label)
        .build()
}

/**
 * Dynamic expressions need renderer schema 1.2+ (Wear OS 4+). Older renderers must get plain
 * static text. Delete this guard once the module moves to Tiles 1.6 / ProtoLayout 1.4.
 */
fun supportsDynamicExpressions(deviceParams: DeviceParameters): Boolean {
    val version = deviceParams.rendererSchemaVersion
    return version.major > 1 || (version.major == 1 && version.minor >= 200)
}
