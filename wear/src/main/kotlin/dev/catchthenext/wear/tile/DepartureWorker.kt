package dev.catchthenext.wear.tile

import android.content.Context
import androidx.wear.tiles.TileService
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.catchthenext.wear.WearGraph
import dev.catchthenext.wear.location.closestTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class DepartureWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val dataStore = TileDataStore(applicationContext)
        val cache = dataStore.read()

        val lat = cache.lat ?: return Result.success()
        val lon = cache.lon ?: return Result.success()

        val favoritesManager = WearGraph.favoritesManager(applicationContext)
        val favorites = withContext(Dispatchers.IO) { favoritesManager.getFavorites() }
        if (favorites.isEmpty()) return Result.success()

        val closest = favorites.closestTo(lat, lon) ?: return Result.success()

        return runCatching {
            val client = WearGraph.transitlandClient()
            val deps = withContext(Dispatchers.IO) { client.getDepartures(closest.id) }
            val now = System.currentTimeMillis()
            dataStore.updateDepartures(closest, deps.map { dep ->
                CachedDeparture(
                    routeShortName = dep.routeShortName,
                    headsign = dep.headsign,
                    scheduledEpochMillis = now + dep.departureMinutes * 60_000
                )
            })
            TileService.getUpdater(applicationContext)
                .requestUpdate(ClosestStopTileService::class.java)
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val WORK_NAME = "departure_refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DepartureWorker>(20, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
