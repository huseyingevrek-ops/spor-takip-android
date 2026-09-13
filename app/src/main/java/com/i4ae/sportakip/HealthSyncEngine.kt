package com.i4ae.sportakip

import android.content.Context
import androidx.documentfile.provider.DocumentFile
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

    const val AUTO_FILE = "SporTakip_Auto.csv"
    const val EXERCISE_FILE = "SporTakip_Egzersizler.csv"
    const val SLEEP_FILE = "SporTakip_Uyku.csv"
    const val HEART_FILE = "SporTakip_Nabiz.csv"
    const val MEASUREMENTS_FILE = "SporTakip_Olcumler.csv"

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
        val treeUri = AppPrefs.treeUri(context)
            ?: return remember(context, false, "Drive klasörü seçilmedi")
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: return remember(context, false, "Drive klasörüne erişilemiyor")

        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        val grantedData = dataReadPermissions().filterTo(mutableSetOf()) { it in granted }
        if (grantedData.isEmpty()) return remember(context, false, "Health Connect okuma izni verilmedi")

        val historyFeature = client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        val historyGranted = !historyFeature || HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted
        val firstImport = !AppPrefs.historyImported(context)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startDate = when {
            firstImport && historyGranted -> LocalDate.of(2000, 1, 1)
            firstImport -> today.minusDays(30)
            else -> today.minusDays(14)
        }
        val endDateExclusive = today.plusDays(1)
        val stamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(java.time.ZonedDateTime.now())

        val dailyHeader = "Tarih,Adim,MesafeKm,AktifKaloriKcal,ToplamKaloriKcal,EgzersizDakika,UykuSaat,OrtalamaNabiz,MinNabiz,MaxNabiz,DinlenikNabiz,KiloKg,VucutYagYuzde,VO2Max,SuLitre,CikilanKat,YukselisMetre,Kaynak,SonSenkronizasyon"
        val heartHeader = "Tarih,OrtalamaNabiz,MinNabiz,MaxNabiz,DinlenikNabiz,Kaynak,SonSenkronizasyon"
        val exerciseHeader = "RecordId,Tarih,Baslangic,Bitis,TurKodu,Baslik,SureDakika,Not,Kaynak,SonSenkronizasyon"
        val sleepHeader = "RecordId,Tarih,Baslangic,Bitis,SureDakika,Kaynak,SonSenkronizasyon"
        val measurementsHeader = "RecordId,TarihSaat,KiloKg,VucutYagYuzde,VO2Max,Kaynak,SonSenkronizasyon"

        val dailyRows = if (firstImport && historyGranted) mutableMapOf() else readExisting(tree, context, AUTO_FILE, dailyHeader, 0)
        val heartRows = if (firstImport && historyGranted) mutableMapOf() else readExisting(tree, context, HEART_FILE, heartHeader, 0)
        val exerciseRows = if (firstImport && historyGranted) mutableMapOf() else readExisting(tree, context, EXERCISE_FILE, exerciseHeader, 0)
        val sleepRows = if (firstImport && historyGranted) mutableMapOf() else readExisting(tree, context, SLEEP_FILE, sleepHeader, 0)
        val measurementRows = if (firstImport && historyGranted) mutableMapOf() else readExisting(tree, context, MEASUREMENTS_FILE, measurementsHeader, 0)

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

        data class Extra(var bodyFat: Double? = null, var vo2: Double? = null)
        val extras = mutableMapOf<LocalDate, Extra>()

        val instantStart = startDate.atStartOfDay(zone).toInstant()
        val instantEnd = endDateExclusive.atStartOfDay(zone).toInstant()
        val instantFilter = TimeRangeFilter.between(instantStart, instantEnd)

        if (HealthPermission.getReadPermission(BodyFatRecord::class) in grantedData) {
            readAll<BodyFatRecord>(client, instantFilter).forEach { r ->
                val date = r.time.atZone(zone).toLocalDate()
                extras.getOrPut(date) { Extra() }.bodyFat = r.percentage.value
                measurementRows[r.metadata.id] = csvLine(
                    r.metadata.id,
                    isoTime(r.time, zone),
                    "",
                    fmt(r.percentage.value),
                    "",
                    "HealthConnect",
                    stamp
                )
            }
        }
        if (HealthPermission.getReadPermission(Vo2MaxRecord::class) in grantedData) {
            readAll<Vo2MaxRecord>(client, instantFilter).forEach { r ->
                val date = r.time.atZone(zone).toLocalDate()
                extras.getOrPut(date) { Extra() }.vo2 = r.vo2MillilitersPerMinuteKilogram
                measurementRows[r.metadata.id] = csvLine(
                    r.metadata.id,
                    isoTime(r.time, zone),
                    "",
                    "",
                    fmt(r.vo2MillilitersPerMinuteKilogram),
                    "HealthConnect",
                    stamp
                )
            }
        }
        if (HealthPermission.getReadPermission(WeightRecord::class) in grantedData) {
            readAll<WeightRecord>(client, instantFilter).forEach { r ->
                measurementRows[r.metadata.id] = csvLine(
                    r.metadata.id,
                    isoTime(r.time, zone),
                    fmt(r.weight.inKilograms),
                    "",
                    "",
                    "HealthConnect",
                    stamp
                )
            }
        }

        if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) in grantedData) {
            readAll<ExerciseSessionRecord>(client, instantFilter).forEach { r ->
                val date = r.startTime.atZone(zone).toLocalDate()
                exerciseRows[r.metadata.id] = csvLine(
                    r.metadata.id,
                    date,
                    isoTime(r.startTime, zone),
                    isoTime(r.endTime, zone),
                    r.exerciseType,
                    r.title ?: "",
                    Duration.between(r.startTime, r.endTime).toMinutes(),
                    r.notes ?: "",
                    "HealthConnect",
                    stamp
                )
            }
        }
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) in grantedData) {
            readAll<SleepSessionRecord>(client, instantFilter).forEach { r ->
                val date = r.startTime.atZone(zone).toLocalDate()
                sleepRows[r.metadata.id] = csvLine(
                    r.metadata.id,
                    date,
                    isoTime(r.startTime, zone),
                    isoTime(r.endTime, zone),
                    Duration.between(r.startTime, r.endTime).toMinutes(),
                    "HealthConnect",
                    stamp
                )
            }
        }

        var todaySteps = 0L
        if (dailyMetrics.isNotEmpty()) {
            val grouped = client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = dailyMetrics,
                    timeRangeFilter = TimeRangeFilter.between(startDate.atStartOfDay(), endDateExclusive.atStartOfDay()),
                    timeRangeSlicer = Period.ofDays(1)
                )
            )
            for (bucket in grouped) {
                val date = bucket.startTime.toLocalDate()
                val r = bucket.result
                val steps = r[StepsRecord.COUNT_TOTAL]
                if (date == today) todaySteps = steps ?: 0L
                val extra = extras[date]
                dailyRows[date.toString()] = csvLine(
                    date,
                    steps ?: "",
                    r[DistanceRecord.DISTANCE_TOTAL]?.inKilometers?.let(::fmt) ?: "",
                    r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories?.let(::fmt) ?: "",
                    r[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories?.let(::fmt) ?: "",
                    r[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMinutes() ?: "",
                    r[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMinutes()?.div(60.0)?.let(::fmt) ?: "",
                    r[HeartRateRecord.BPM_AVG] ?: "",
                    r[HeartRateRecord.BPM_MIN] ?: "",
                    r[HeartRateRecord.BPM_MAX] ?: "",
                    r[RestingHeartRateRecord.BPM_AVG] ?: "",
                    r[WeightRecord.WEIGHT_AVG]?.inKilograms?.let(::fmt) ?: "",
                    extra?.bodyFat?.let(::fmt) ?: "",
                    extra?.vo2?.let(::fmt) ?: "",
                    r[HydrationRecord.VOLUME_TOTAL]?.inLiters?.let(::fmt) ?: "",
                    r[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL]?.let(::fmt) ?: "",
                    r[ElevationGainedRecord.ELEVATION_GAINED_TOTAL]?.inMeters?.let(::fmt) ?: "",
                    "HealthConnect",
                    stamp
                )
                heartRows[date.toString()] = csvLine(
                    date,
                    r[HeartRateRecord.BPM_AVG] ?: "",
                    r[HeartRateRecord.BPM_MIN] ?: "",
                    r[HeartRateRecord.BPM_MAX] ?: "",
                    r[RestingHeartRateRecord.BPM_AVG] ?: "",
                    "HealthConnect",
                    stamp
                )
            }
        }

        writeCsv(tree, context, AUTO_FILE, dailyHeader, dailyRows)
        writeCsv(tree, context, HEART_FILE, heartHeader, heartRows)
        writeCsv(tree, context, EXERCISE_FILE, exerciseHeader, exerciseRows)
        writeCsv(tree, context, SLEEP_FILE, sleepHeader, sleepRows)
        writeCsv(tree, context, MEASUREMENTS_FILE, measurementsHeader, measurementRows)

        if (firstImport && historyGranted) AppPrefs.setHistoryImported(context, true)
        val full = !firstImport || historyGranted
        val permissionCount = grantedData.size
        val msg = when {
            firstImport && historyGranted -> "Tüm geçmiş senkronize edildi ($permissionCount veri izni)"
            firstImport -> "Geçmiş izni verilmedi; son 30 gün senkronize edildi"
            else -> "Senkronizasyon başarılı"
        }
        return remember(context, true, msg, todaySteps, full)
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

    private fun readExisting(
        tree: DocumentFile,
        context: Context,
        fileName: String,
        expectedHeader: String,
        keyColumn: Int
    ): MutableMap<String, String> {
        val rows = mutableMapOf<String, String>()
        val file = tree.findFile(fileName) ?: return rows
        try {
            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                val header = reader.readLine() ?: return@use
                if (header != expectedHeader) return@use
                reader.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    val cols = parseCsv(line)
                    if (cols.size > keyColumn && cols[keyColumn].isNotBlank()) rows[cols[keyColumn]] = line
                }
            }
        } catch (_: Exception) {
        }
        return rows
    }

    private fun writeCsv(
        tree: DocumentFile,
        context: Context,
        fileName: String,
        header: String,
        rows: Map<String, String>
    ) {
        val file = tree.findFile(fileName) ?: tree.createFile("text/csv", fileName)
            ?: throw IllegalStateException("$fileName oluşturulamadı")
        val output = buildString {
            appendLine(header)
            rows.toSortedMap().forEach { (_, line) -> appendLine(line) }
        }
        context.contentResolver.openOutputStream(file.uri, "wt")?.bufferedWriter()?.use { it.write(output) }
            ?: throw IllegalStateException("$fileName yazılamadı")
    }

    private fun parseCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"'); i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        out += current.toString()
        return out
    }

    private fun csvLine(vararg values: Any?): String = values.joinToString(",") { csv(it?.toString() ?: "") }

    private fun csv(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
        return "\"${value.replace("\"", "\"\"")}\""
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)

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
