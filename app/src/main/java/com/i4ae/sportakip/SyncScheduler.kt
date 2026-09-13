package com.i4ae.sportakip

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val WORK_NAME = "spor_takip_health_sync"

    fun apply(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(WORK_NAME)
        if (!AppPrefs.autoSync(context) || AppPrefs.treeUri(context) == null) return

        val perDay = AppPrefs.frequencyPerDay(context).coerceIn(1, 8)
        val intervalHours = (24 / perDay).coerceAtLeast(3)
        val startHour = AppPrefs.startHour(context).coerceIn(0, 23)
        val now = ZonedDateTime.now()
        var first = now.withHour(startHour).withMinute(0).withSecond(0).withNano(0)
        while (!first.isAfter(now)) first = first.plusHours(intervalHours.toLong())
        val delayMinutes = Duration.between(now, first).toMinutes().coerceAtLeast(1)

        val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(intervalHours.toLong(), TimeUnit.HOURS)
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .build()

        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
