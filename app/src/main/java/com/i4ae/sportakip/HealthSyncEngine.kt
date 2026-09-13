package com.i4ae.sportakip

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object HealthSyncEngine {
    data class Result(
        val ok: Boolean,
        val message: String,
        val todaySteps: Long = 0,
        val fullHistory: Boolean = false
    )

    private val stepsPermission = HealthPermission.getReadPermission(StepsRecord::class)

    fun dataReadPermissions(): Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
        HealthPermission.getReadPermission(HydrationRecord::class),
        HealthPermission.getReadPermission(FloorsClimbedRecord::class),
        HealthPermission.getReadPermission(ElevationGainedRecord::class)
    )

    suspend fun todaySteps(context: Context): Long {
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        if (stepsPermission !in granted) throw SecurityException("Adım okuma izni yok")
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val result = client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(start, Instant.now())
            )
        )
        return result[StepsRecord.COUNT_TOTAL] ?: 0L
    }

    suspend fun sync(context: Context): Result {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return remember(context, false, "Health Connect kullanılamıyor")
        }
        if (AppPrefs.googleAccount(context).isNullOrBlank()) {
            return remember(context, false, "Google hesabı bağlı değil")
        }

        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        val grantedData = dataReadPermissions().filterTo(mutableSetOf()) { it in granted }
        if (grantedData.isEmpty()) return remember(context, false, "Health Connect okuma izni verilmedi")

        val historyFeature = client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        val historyGranted = !historyFeature || HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startDate = if (historyGranted) LocalDate.of(2000, 1, 1) else today.minusDays(30)
        val endDateExclusive = today.plusDays(1)
        val stamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(java.time.ZonedDateTime.now())

        val gunluk = mutableListOf<List<Any?>>()
        val egzersizler = mutableListOf<List<Any?>>()
        val uyku = mutableListOf<List<Any?>>()
        val nabiz = mutableListOf<List<Any?>>()
        val olcumler = mutableListOf<List<Any?>>()

        gunluk += listOf(
            "Tarih", "Adım", "Mesafe (km)", "Aktif Kalori (kcal)", "Toplam Kalori (kcal)",
            "Egzersiz Süresi (dk)", "Uyku (saat)", "Ortalama Nabız (bpm)", "Min Nabız (bpm)",
            "Maks. Nabız (bpm)", "Dinlenik Nabız (bpm)", "Kilo (kg)", "Vücut Yağ (%)", "VO2 Max",
            "Su (L)", "Çıkılan Kat", "Yükseliş (m)", "Kaynak", "Son Senkronizasyon"
        )
        egzersizler += listOf(
            "Başlangıç", "Bitiş", "Tür Kodu", "Başlık", "Süre (dk)", "Mesafe (km)",
            "Kalori (kcal)", "Adım", "Ortalama Nabız (bpm)", "Maks. Nabız (bpm)",
            "Kaynak", "Kayıt ID", "Son Senkronizasyon"
        )
        uyku += listOf("Başlangıç", "Bitiş", "Süre (saat)", "Aşama", "Kaynak", "Kayıt ID", "Son Senkronizasyon", "Tarih")
        nabiz += listOf("Tarih", "Ortalama Nabız (bpm)", "Min Nabız (bpm)", "Maks. Nabız (bpm)", "Dinlenik Nabız (bpm)", "Kaynak", "Son Senkronizasyon", "Not")
        olcumler += listOf("Zaman", "Kilo (kg)", "Vücut Yağ (%)", "VO2 Max", "Kaynak", "Kayıt ID", "Son Senkronizasyon", "Tür")

        val instantStart = startDate.atStartOfDay(zone).toInstant()
        val instantEnd = endDateExclusive.atStartOfDay(zone).toInstant()
        val instantFilter = TimeRangeFilter.between(instantStart, instantEnd)

        data class Extra(var bodyFat: Double? = null, var vo2: Double? = null)
        val extras = mutableMapOf<LocalDate, Extra>()

        if (HealthPermission.getReadPermission(WeightRecord::class) in grantedData) {
            readAll<WeightRecord>(client, instantFilter)
                .sortedBy { it.time }
                .forEach { r ->
                    olcumler += listOf(isoTime(r.time, zone), round2(r.weight.inKilograms), null, null, "HealthConnect", r.metadata.id, stamp, "Kilo")
                }
        }
        if (HealthPermission.getReadPermission(BodyFatRecord::class) in grantedData) {
            readAll<BodyFatRecord>(client, instantFilter)
                .sortedBy { it.time }
                .forEach { r ->
                    val value = r.percentage.value
                    extras.getOrPut(r.time.atZone(zone).toLocalDate()) { Extra() }.bodyFat = value
                    olcumler += listOf(isoTime(r.time, zone), null, round2(value), null, "HealthConnect", r.metadata.id, stamp, "Vücut Yağı")
                }
        }
        if (HealthPermission.getReadPermission(Vo2MaxRecord::class) in grantedData) {
            readAll<Vo2MaxRecord>(client, instantFilter)
                .sortedBy { it.time }
                .forEach { r ->
                    val value = r.vo2MillilitersPerMinuteKilogram
                    extras.getOrPut(r.time.atZone(zone).toLocalDate()) { Extra() }.vo2 = value
                    olcumler += listOf(isoTime(r.time, zone), null, null, round2(value), "HealthConnect", r.metadata.id, stamp, "VO2 Max")
                }
        }

        if (HealthPermission.getReadPermission(SleepSessionRecord::class) in grantedData) {
            readAll<SleepSessionRecord>(client, instantFilter)
                .sortedBy { it.startTime }
                .forEach { r ->
                    uyku += listOf(
                        isoTime(r.startTime, zone),
                        isoTime(r.endTime, zone),
                        round2(Duration.between(r.startTime, r.endTime).toMinutes() / 60.0),
                        "Oturum",
                        "HealthConnect",
                        r.metadata.id,
                        stamp,
                        r.startTime.atZone(zone).toLocalDate().toString()
                    )
                }
        }

        val exerciseMetrics = linkedSetOf<AggregateMetric<*>>()
        if (HealthPermission.getReadPermission(StepsRecord::class) in grantedData) exerciseMetrics += StepsRecord.COUNT_TOTAL
        if (HealthPermission.getReadPermission(DistanceRecord::class) in grantedData) exerciseMetrics += DistanceRecord.DISTANCE_TOTAL
        if (HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class) in grantedData) exerciseMetrics += ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
        if (HealthPermission.getReadPermission(HeartRateRecord::class) in grantedData) {
            exerciseMetrics += HeartRateRecord.BPM_AVG
            exerciseMetrics += HeartRateRecord.BPM_MAX
        }
        if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) in grantedData) {
            readAll<ExerciseSessionRecord>(client, instantFilter)
                .sortedBy { it.startTime }
                .forEach { r ->
                    val agg = if (exerciseMetrics.isNotEmpty()) {
                        client.aggregate(
                            AggregateRequest(
                                metrics = exerciseMetrics,
                                timeRangeFilter = TimeRangeFilter.between(r.startTime, r.endTime)
                            )
                        )
                    } else null
                    egzersizler += listOf(
                        isoTime(r.startTime, zone),
                        isoTime(r.endTime, zone),
                        r.exerciseType,
                        r.title ?: "",
                        Duration.between(r.startTime, r.endTime).toMinutes(),
                        agg?.get(DistanceRecord.DISTANCE_TOTAL)?.inKilometers?.let(::round2),
                        agg?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories?.let(::round2),
                        agg?.get(StepsRecord.COUNT_TOTAL),
                        agg?.get(HeartRateRecord.BPM_AVG),
                        agg?.get(HeartRateRecord.BPM_MAX),
                        "HealthConnect",
                        r.metadata.id,
                        stamp
                    )
                }
        }

        val dailyMetrics = linkedSetOf<AggregateMetric<*>>()
        if (HealthPermission.getReadPermission(StepsRecord::class) in grantedData) dailyMetrics += StepsRecord.COUNT_TOTAL
        if (HealthPermission.getReadPermission(DistanceRecord::class) in grantedData) dailyMetrics += DistanceRecord.DISTANCE_TOTAL
        if (HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class) in grantedData) dailyMetrics += ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
        if (HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class) in grantedData) dailyMetrics += TotalCaloriesBurnedRecord.ENERGY_TOTAL
        if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) in grantedData) dailyMetrics += ExerciseSessionRecord.EXERCISE_DURATION_TOTAL
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) in grantedData) dailyMetrics += SleepSessionRecord.SLEEP_DURATION_TOTAL
        if (HealthPermission.getReadPermission(HeartRateRecord::class) in grantedData) {
            dailyMetrics += HeartRateRecord.BPM_AVG
            dailyMetrics += HeartRateRecord.BPM_MIN
            dailyMetrics += HeartRateRecord.BPM_MAX
        }
        if (HealthPermission.getReadPermission(RestingHeartRateRecord::class) in grantedData) dailyMetrics += RestingHeartRateRecord.BPM_AVG
        if (HealthPermission.getReadPermission(WeightRecord::class) in grantedData) dailyMetrics += WeightRecord.WEIGHT_AVG
        if (HealthPermission.getReadPermission(HydrationRecord::class) in grantedData) dailyMetrics += HydrationRecord.VOLUME_TOTAL
        if (HealthPermission.getReadPermission(FloorsClimbedRecord::class) in grantedData) dailyMetrics += FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL
        if (HealthPermission.getReadPermission(ElevationGainedRecord::class) in grantedData) dailyMetrics += ElevationGainedRecord.ELEVATION_GAINED_TOTAL

        var todaySteps = 0L
        if (dailyMetrics.isNotEmpty()) {
            val grouped = client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = dailyMetrics,
                    timeRangeFilter = TimeRangeFilter.between(startDate.atStartOfDay(), endDateExclusive.atStartOfDay()),
                    timeRangeSlicer = Period.ofDays(1)
                )
            )
            grouped.sortedBy { it.startTime }.forEach { bucket ->
                val date = bucket.startTime.toLocalDate()
                val r = bucket.result
                val steps = r[StepsRecord.COUNT_TOTAL]
                if (date == today) todaySteps = steps ?: 0L
                val extra = extras[date]
                gunluk += listOf(
                    date.toString(),
                    steps,
                    r[DistanceRecord.DISTANCE_TOTAL]?.inKilometers?.let(::round2),
                    r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories?.let(::round2),
                    r[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories?.let(::round2),
                    r[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMinutes(),
                    r[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMinutes()?.div(60.0)?.let(::round2),
                    r[HeartRateRecord.BPM_AVG],
                    r[HeartRateRecord.BPM_MIN],
                    r[HeartRateRecord.BPM_MAX],
                    r[RestingHeartRateRecord.BPM_AVG],
                    r[WeightRecord.WEIGHT_AVG]?.inKilograms?.let(::round2),
                    extra?.bodyFat?.let(::round2),
                    extra?.vo2?.let(::round2),
                    r[HydrationRecord.VOLUME_TOTAL]?.inLiters?.let(::round2),
                    r[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL]?.let(::round2),
                    r[ElevationGainedRecord.ELEVATION_GAINED_TOTAL]?.inMeters?.let(::round2),
                    "HealthConnect",
                    stamp
                )
                nabiz += listOf(
                    date.toString(),
                    r[HeartRateRecord.BPM_AVG],
                    r[HeartRateRecord.BPM_MIN],
                    r[HeartRateRecord.BPM_MAX],
                    r[RestingHeartRateRecord.BPM_AVG],
                    "HealthConnect",
                    stamp,
                    "Günlük özet"
                )
            }
        }

        GoogleSheetsClient.replaceAll(
            context,
            linkedMapOf(
                "Gunluk" to gunluk,
                "Egzersizler" to egzersizler,
                "Uyku" to uyku,
                "Nabiz" to nabiz,
                "Olcumler" to olcumler
            )
        )

        if (historyGranted) AppPrefs.setHistoryImported(context, true)
        val msg = if (historyGranted) {
            "Google Sheet güncellendi; tüm geçmiş senkronize edildi"
        } else {
            "Google Sheet güncellendi; geçmiş izni yok, son 30 gün aktarıldı"
        }
        return remember(context, true, msg, todaySteps, historyGranted)
    }

    private suspend inline fun <reified T : Record> readAll(
        client: HealthConnectClient,
        timeRangeFilter: TimeRangeFilter
    ): List<T> {
        val out = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = T::class,
                    timeRangeFilter = timeRangeFilter,
                    pageToken = pageToken,
                    pageSize = 1000
                )
            )
            out += response.records
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
        return out
    }

    private fun round2(value: Double): Double = String.format(Locale.US, "%.2f", value).toDouble()

    private fun isoTime(instant: Instant, zone: ZoneId): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atZone(zone))

    private fun remember(
        context: Context,
        ok: Boolean,
        message: String,
        today: Long = 0,
        fullHistory: Boolean = false
    ): Result {
        val stamp = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").format(LocalDateTime.now())
        AppPrefs.setLastResult(context, stamp, message)
        return Result(ok, message, today, fullHistory)
    }
}
