package com.i4ae.sportakip

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object HealthSyncEngine {
    data class Result(val ok: Boolean, val message: String, val todaySteps: Long = 0)

    private val readSteps = HealthPermission.getReadPermission(StepsRecord::class)
    const val AUTO_FILE = "SporTakip_Auto.csv"

    suspend fun todaySteps(context: Context): Long {
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        if (readSteps !in granted) throw SecurityException("Adım okuma izni yok")
        return stepsForDate(client, LocalDate.now())
    }

    suspend fun sync(context: Context): Result {
        val status = HealthConnectClient.getSdkStatus(context)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            return remember(context, false, "Health Connect kullanılamıyor")
        }
        val treeUri = AppPrefs.treeUri(context)
            ?: return remember(context, false, "Drive klasörü seçilmedi")

        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        if (readSteps !in granted) return remember(context, false, "Adım okuma izni yok")

        val rows = mutableMapOf<String, String>()
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: return remember(context, false, "Drive klasörüne erişilemiyor")
        val file = tree.findFile(AUTO_FILE) ?: tree.createFile("text/csv", AUTO_FILE)
            ?: return remember(context, false, "CSV oluşturulamadı")

        try {
            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.useLines { lines ->
                lines.drop(1).filter { it.isNotBlank() }.forEach { line ->
                    val date = line.substringBefore(',')
                    if (date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) rows[date] = line
                }
            }
        } catch (_: Exception) {
        }

        val zone = ZoneId.systemDefault()
        val stamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(java.time.ZonedDateTime.now())
        var today = 0L
        for (offset in 0..6) {
            val date = LocalDate.now(zone).minusDays(offset.toLong())
            val steps = stepsForDate(client, date)
            if (offset == 0) today = steps
            rows[date.toString()] = "${date},${steps},HealthConnect,${stamp}"
        }

        val output = buildString {
            appendLine("Tarih,Adim,Kaynak,SonSenkronizasyon")
            rows.toSortedMap().forEach { (_, line) -> appendLine(line) }
        }
        context.contentResolver.openOutputStream(file.uri, "wt")?.bufferedWriter()?.use { it.write(output) }
            ?: return remember(context, false, "Drive dosyasına yazılamadı")

        return remember(context, true, "Başarılı", today)
    }

    private suspend fun stepsForDate(client: HealthConnectClient, date: LocalDate): Long {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant()
        val next = date.plusDays(1).atStartOfDay(zone).toInstant()
        val end = if (date == LocalDate.now(zone)) Instant.now() else next
        val result: AggregationResult = client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        )
        return result[StepsRecord.COUNT_TOTAL] ?: 0L
    }

    private fun remember(context: Context, ok: Boolean, message: String, today: Long = 0): Result {
        val stamp = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").format(java.time.LocalDateTime.now())
        AppPrefs.setLastResult(context, stamp, message)
        return Result(ok, message, today)
    }
}
