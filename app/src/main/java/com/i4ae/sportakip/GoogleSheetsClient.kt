package com.i4ae.sportakip

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

object GoogleSheetsClient {
    const val SPREADSHEET_ID = "1BuJL0CUrHw8WG8hwl6Fwp8B1E22_AN2Nz-PoDfP2poo"
    const val SPREADSHEET_URL = "https://docs.google.com/spreadsheets/d/$SPREADSHEET_ID/edit"
    private const val SCOPE = "oauth2:https://www.googleapis.com/auth/spreadsheets"

    data class SyncStats(
        val inserted: Int,
        val updated: Int,
        val unchanged: Int
    )

    private data class SheetSpec(val keyColumn: Int)

    private val sheetSpecs = linkedMapOf(
        "Gunluk" to SheetSpec(keyColumn = 0),
        "Egzersizler" to SheetSpec(keyColumn = 11),
        "Uyku" to SheetSpec(keyColumn = 5),
        "Nabiz" to SheetSpec(keyColumn = 0),
        "Olcumler" to SheetSpec(keyColumn = 5)
    )

    suspend fun ensureAuthorized(context: Context): String = withContext(Dispatchers.IO) {
        getToken(context)
    }

    suspend fun upsert(context: Context, data: Map<String, List<List<Any?>>>): SyncStats =
        withContext(Dispatchers.IO) {
            var token = getToken(context)
            try {
                upsertAll(token, data)
            } catch (e: UnauthorizedException) {
                GoogleAuthUtil.clearToken(context.applicationContext, token)
                token = getToken(context)
                upsertAll(token, data)
            }
        }

    private fun getToken(context: Context): String {
        val accountName = AppPrefs.googleAccount(context)
            ?: throw IllegalStateException("Google hesabı bağlı değil")
        val account = Account(accountName, GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE)
        return GoogleAuthUtil.getToken(context.applicationContext, account, SCOPE)
    }

    private fun upsertAll(token: String, data: Map<String, List<List<Any?>>>): SyncStats {
        val relevantData = data.filterKeys { it in sheetSpecs }
        if (relevantData.isEmpty()) return SyncStats(0, 0, 0)

        val existing = readExisting(token, relevantData.keys)
        val writeItems = JSONArray()
        var inserted = 0
        var updated = 0
        var unchanged = 0

        relevantData.forEach { (sheet, incomingRows) ->
            if (incomingRows.isEmpty()) return@forEach

            val spec = sheetSpecs.getValue(sheet)
            val header = incomingRows.first()
            val width = header.size.coerceAtLeast(1)
            val existingRows = existing[sheet].orEmpty()

            if (existingRows.isEmpty() || !sameRow(existingRows.first(), header, width)) {
                addWrite(writeItems, sheet, 1, listOf(header), width)
            }

            val existingByKey = linkedMapOf<String, Pair<Int, List<Any?>>>()
            existingRows.drop(1).forEachIndexed { index, row ->
                val key = keyOf(row, spec.keyColumn)
                if (key.isNotBlank() && key !in existingByKey) {
                    existingByKey[key] = (index + 2) to row
                }
            }

            val appendRows = mutableListOf<List<Any?>>()
            var nextRow = (existingRows.size + 1).coerceAtLeast(2)

            incomingRows.drop(1).forEach { incoming ->
                val key = keyOf(incoming, spec.keyColumn)
                if (key.isBlank()) return@forEach

                val found = existingByKey[key]
                if (found == null) {
                    val normalized = normalizeRow(incoming, width)
                    appendRows += normalized
                    existingByKey[key] = nextRow to normalized
                    nextRow++
                    inserted++
                } else {
                    val (rowNumber, oldRow) = found
                    val merged = mergeRow(oldRow, incoming, width)
                    if (sameRow(oldRow, merged, width)) {
                        unchanged++
                    } else {
                        addWrite(writeItems, sheet, rowNumber, listOf(merged), width)
                        existingByKey[key] = rowNumber to merged
                        updated++
                    }
                }
            }

            if (appendRows.isNotEmpty()) {
                val appendStart = (existingRows.size + 1).coerceAtLeast(2)
                addWrite(writeItems, sheet, appendStart, appendRows, width)
            }
        }

        if (writeItems.length() > 0) {
            val body = JSONObject()
                .put("valueInputOption", "RAW")
                .put("data", writeItems)
            request(
                method = "POST",
                url = "https://sheets.googleapis.com/v4/spreadsheets/$SPREADSHEET_ID/values:batchUpdate",
                token = token,
                body = body.toString()
            )
        }

        return SyncStats(inserted, updated, unchanged)
    }

    private fun readExisting(token: String, sheets: Set<String>): Map<String, List<List<Any?>>> {
        val query = sheets.joinToString("&") { sheet ->
            "ranges=" + URLEncoder.encode("$sheet!A:Z", Charsets.UTF_8.name())
        }
        val text = request(
            method = "GET",
            url = "https://sheets.googleapis.com/v4/spreadsheets/$SPREADSHEET_ID/values:batchGet?$query&majorDimension=ROWS",
            token = token
        )
        val root = JSONObject(text)
        val valueRanges = root.optJSONArray("valueRanges") ?: JSONArray()
        val result = linkedMapOf<String, List<List<Any?>>>()

        sheets.forEachIndexed { index, sheet ->
            val values = valueRanges.optJSONObject(index)?.optJSONArray("values") ?: JSONArray()
            result[sheet] = jsonRows(values)
        }
        return result
    }

    private fun jsonRows(values: JSONArray): List<List<Any?>> {
        val rows = mutableListOf<List<Any?>>()
        for (i in 0 until values.length()) {
            val arr = values.optJSONArray(i) ?: JSONArray()
            val row = mutableListOf<Any?>()
            for (j in 0 until arr.length()) {
                val value = arr.opt(j)
                row += if (value == null || value == JSONObject.NULL) null else value
            }
            rows += row
        }
        return rows
    }

    private fun mergeRow(oldRow: List<Any?>, incoming: List<Any?>, width: Int): List<Any?> {
        val old = normalizeRow(oldRow, width)
        val fresh = normalizeRow(incoming, width)
        return List(width) { index ->
            val value = fresh[index]
            if (isBlankValue(value)) old[index] else value
        }
    }

    private fun normalizeRow(row: List<Any?>, width: Int): List<Any?> =
        List(width) { index -> row.getOrNull(index) }

    private fun keyOf(row: List<Any?>, index: Int): String =
        row.getOrNull(index)?.toString()?.trim().orEmpty()

    private fun sameRow(a: List<Any?>, b: List<Any?>, width: Int): Boolean {
        val left = normalizeRow(a, width)
        val right = normalizeRow(b, width)
        return (0 until width).all { sameValue(left[it], right[it]) }
    }

    private fun sameValue(a: Any?, b: Any?): Boolean {
        if (isBlankValue(a) && isBlankValue(b)) return true
        if (a is Number && b is Number) return abs(a.toDouble() - b.toDouble()) < 0.0000001
        return a?.toString()?.trim() == b?.toString()?.trim()
    }

    private fun isBlankValue(value: Any?): Boolean =
        value == null || value == JSONObject.NULL || (value is String && value.isBlank())

    private fun addWrite(
        items: JSONArray,
        sheet: String,
        startRow: Int,
        rows: List<List<Any?>>,
        width: Int
    ) {
        if (rows.isEmpty()) return
        val rowArray = JSONArray()
        rows.forEach { row ->
            val arr = JSONArray()
            normalizeRow(row, width).forEach { value ->
                when (value) {
                    null, JSONObject.NULL -> arr.put(JSONObject.NULL)
                    is Number, is Boolean -> arr.put(value)
                    else -> arr.put(value.toString())
                }
            }
            rowArray.put(arr)
        }
        val endColumn = columnName(width)
        val endRow = startRow + rows.size - 1
        items.put(
            JSONObject()
                .put("range", "$sheet!A$startRow:$endColumn$endRow")
                .put("majorDimension", "ROWS")
                .put("values", rowArray)
        )
    }

    private fun request(method: String, url: String, token: String, body: String? = null): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30_000
            readTimeout = 60_000
            doInput = true
            doOutput = body != null
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            if (body != null) setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        if (body != null) {
            conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let { s -> BufferedReader(InputStreamReader(s)).use { it.readText() } } ?: ""
        conn.disconnect()
        if (code == 401) throw UnauthorizedException()
        if (code !in 200..299) {
            val detail = try {
                JSONObject(text).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
            throw IllegalStateException("Google Sheet erişim hatası ($code): ${detail ?: text.take(250)}")
        }
        return text
    }

    private fun columnName(count: Int): String {
        var n = count.coerceAtLeast(1)
        val out = StringBuilder()
        while (n > 0) {
            n--
            out.append(('A'.code + (n % 26)).toChar())
            n /= 26
        }
        return out.reverse().toString()
    }

    private class UnauthorizedException : Exception()
}
