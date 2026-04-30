package dev.catchthenext.android.storage

import dev.catchthenext.api.TransitApi
import dev.catchthenext.api.TransitlandClient
import dev.catchthenext.model.Departure
import dev.catchthenext.model.FeedAttribution
import dev.catchthenext.model.Stop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AttributionRecordingClient(
    private val delegate: TransitlandClient,
    private val store: AttributionStore,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) : TransitApi {

    override fun getNearbyStops(lat: Double, lon: Double, radiusMeters: Int, limit: Int): List<Stop> {
        val result = delegate.getNearbyStops(lat, lon, radiusMeters, limit)
        recordFeeds(result.mapNotNull { it.feed })
        return result
    }

    override fun getDepartures(stopId: Long, nextSeconds: Int): List<Departure> {
        val result = delegate.getDepartures(stopId, nextSeconds)
        recordFeeds(result.mapNotNull { it.feed })
        return result
    }

    override fun getDeparturesBatch(stopIds: List<Long>, nextSeconds: Int): Map<Long, List<Departure>> {
        val result = delegate.getDeparturesBatch(stopIds, nextSeconds)
        recordFeeds(result.values.flatten().mapNotNull { it.feed }.distinctBy { it.feedOnestopId })
        return result
    }

    private fun recordFeeds(feeds: List<FeedAttribution>) {
        if (feeds.isEmpty()) return
        scope.launch { runCatching { store.recordSeen(feeds) } }
    }
}
