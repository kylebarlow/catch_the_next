package dev.catchthenext.android.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.catchthenext.storage.FavoritesManager
import java.util.concurrent.TimeUnit

class FavoritesSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val getFavorites = favoritesFactory ?: return Result.failure()
        val getMetaStore = metaStoreFactory ?: return Result.failure()
        FavoritesSyncListener.coldStartReconcile(
            applicationContext,
            getFavorites(applicationContext),
            getMetaStore(applicationContext),
        )
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "favorites_sync"

        @Volatile private var favoritesFactory: ((Context) -> FavoritesManager)? = null
        @Volatile private var metaStoreFactory: ((Context) -> SyncMetadataStore)? = null

        fun configure(
            getFavorites: (Context) -> FavoritesManager,
            getMetaStore: (Context) -> SyncMetadataStore,
        ) {
            favoritesFactory = getFavorites
            metaStoreFactory = getMetaStore
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<FavoritesSyncWorker>(30, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
