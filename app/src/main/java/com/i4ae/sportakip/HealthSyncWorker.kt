package com.i4ae.sportakip

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class HealthSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            val result = HealthSyncEngine.sync(applicationContext)
            if (result.ok) Result.success() else Result.retry()
        } catch (_: SecurityException) {
            Result.failure()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
