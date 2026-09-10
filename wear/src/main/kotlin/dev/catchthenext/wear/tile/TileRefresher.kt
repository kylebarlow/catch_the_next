package dev.catchthenext.wear.tile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide home for the tile's background refresh.
 *
 * The scope deliberately outlives any [ClosestStopTileService] instance: the tile host unbinds the
 * service as soon as the user swipes away, and a scope owned by the service would cancel a fetch
 * (and its departures write) mid-flight. Note this does not buy background location — with no
 * `ACCESS_BACKGROUND_LOCATION` the OS may still deny a fix once the process leaves the foreground;
 * what it buys is that a fix which *does* arrive gets persisted and cached for the next tile
 * request, and that the departures write always completes.
 *
 * [inFlight] is shared for the same reason: the enter event and the tile request can arrive
 * together — and against two service instances — but the pipeline (and its location lookup) must
 * only run once.
 */
object TileRefresher {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val inFlight = AtomicBoolean(false)
}
