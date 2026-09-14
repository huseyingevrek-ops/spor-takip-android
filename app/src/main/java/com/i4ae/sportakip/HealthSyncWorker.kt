package com.i4ae.sportakip

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.auth.UserRecoverableAuthException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class HealthSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            GoogleSheetsClient.ensureAuthorized(applicationContext)
            val result = HealthSyncEngine.sync(applicationContext)
            if (result.ok) Result.success() else Result.retry()
        } catch (_: UserRecoverableAuthException) {
            remember("Google Sheet erişim izni gerekiyor")
            Result.failure()
        } catch (e: SecurityException) {
            remember(e.message ?: "Health Connect erişim hatası")
            Result.failure()
        } catch (e: Exception) {
            val raw = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            val msg = if (raw.contains("UnregisteredOnApiConsole", ignoreCase = true)) {
                "Google OAuth Android istemcisi kayıtlı değil (UnregisteredOnApiConsole)"
            } else {
                "Senkronizasyon hatası: $raw"
            }
            remember(msg)
            Result.retry()
        }
    }

    private fun remember(message: String) {
        val stamp = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").format(LocalDateTime.now())
        AppPrefs.setLastResult(applicationContext, stamp, message)
    }
}
