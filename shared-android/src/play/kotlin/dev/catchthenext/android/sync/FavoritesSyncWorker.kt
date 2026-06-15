package dev.catchthenext.android.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class FavoritesSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val getStore = storeFactory ?: return Result.failure()
        val store = getStore(applicationContext)
        FavoritesSyncListener.coldStartReconcile(applicationContext, store)
        FavoritesSyncPublisher.republish(applicationContext, store)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "favorites_sync"

        @Volatile private var storeFactory: ((Context) -> SyncStateStore)? = null

        fun configure(getStore: (Context) -> SyncStateStore) {
            storeFactory = getStore
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
