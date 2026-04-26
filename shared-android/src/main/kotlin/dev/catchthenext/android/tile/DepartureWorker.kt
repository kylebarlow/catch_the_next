package dev.catchthenext.android.tile

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.catchthenext.android.location.LatLon
import dev.catchthenext.android.storage.DistanceUnitStore
import dev.catchthenext.api.TransitlandClient
import dev.catchthenext.storage.FavoritesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class DepartureWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val getClient = clientProvider ?: return Result.failure()
        val getFavorites = favoritesFactory ?: return Result.failure()

        val dataStore = TileDataStore(applicationContext)
        val cache = dataStore.read()

        val lat = cache.lat ?: return Result.success()
        val lon = cache.lon ?: return Result.success()

        val favoritesManager = getFavorites(applicationContext)
        val favorites = withContext(Dispatchers.IO) { favoritesManager.getFavorites() }
        if (favorites.isEmpty()) return Result.success()

        val client = getClient()
        val threshold = DistanceUnitStore(applicationContext).thresholdMetersFlow.first()

        val state = withContext(Dispatchers.IO) {
            computeTileState(
                favorites = favorites,
                location = LatLon(lat, lon),
                hasPermission = true,
                thresholdMeters = threshold,
                fetchDepartures = makeFetchNetworkDepartures({ client.getDepartures(it) }, cache),
                persistDepartures = { stops -> dataStore.updateNearbyDepartures(stops) },
            )
        }

        return when (state) {
            is TileState.Ready -> {
                DeparturesRefreshCallbacks.fireAll(applicationContext)
                Result.success()
            }
            is TileState.NetworkError -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "departure_refresh"

        @Volatile private var clientProvider: (() -> TransitlandClient)? = null
        @Volatile private var favoritesFactory: ((Context) -> FavoritesManager)? = null

        fun configure(
            getClient: () -> TransitlandClient,
            getFavorites: (Context) -> FavoritesManager,
        ) {
            clientProvider = getClient
            favoritesFactory = getFavorites
        }

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
