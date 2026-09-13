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

object GoogleSheetsClient {
    const val SPREADSHEET_ID = "1BuJL0CUrHw8WG8hwl6Fwp8B1E22_AN2Nz-PoDfP2poo"
    const val SPREADSHEET_URL = "https://docs.google.com/spreadsheets/d/$SPREADSHEET_ID/edit"
    private const val SCOPE = "oauth2:https://www.googleapis.com/auth/spreadsheets"

    suspend fun ensureAuthorized(context: Context): String = withContext(Dispatchers.IO) {
        getToken(context)
    }

    suspend fun replaceAll(context: Context, data: Map<String, List<List<Any?>>>) = withContext(Dispatchers.IO) {
        var token = getToken(context)
        try {
            writeAll(token, data)
        } catch (e: UnauthorizedException) {
            GoogleAuthUtil.clearToken(context.applicationContext, token)
            token = getToken(context)
            writeAll(token, data)
        }
    }

    private fun getToken(context: Context): String {
        val accountName = AppPrefs.googleAccount(context)
            ?: throw IllegalStateException("Google hesabı bağlı değil")
        val account = Account(accountName, GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE)
        return GoogleAuthUtil.getToken(context.applicationContext, account, SCOPE)
    }

    private fun writeAll(token: String, data: Map<String, List<List<Any?>>>) {
        val clearBody = JSONObject().put(
            "ranges",
            JSONArray(data.keys.map { "$it!A:Z" })
        )
        request(
            method = "POST",
            url = "https://sheets.googleapis.com/v4/spreadsheets/$SPREADSHEET_ID/values:batchClear",
            token = token,
            body = clearBody.toString()
        )

        val items = JSONArray()
        data.forEach { (sheet, rows) ->
            val rowArray = JSONArray()
            rows.forEach { row ->
                val arr = JSONArray()
                row.forEach { value ->
                    when (value) {
                        null -> arr.put(JSONObject.NULL)
                        is Number, is Boolean -> arr.put(value)
                        else -> arr.put(value.toString())
                    }
                }
                rowArray.put(arr)
            }
            val endColumn = columnName(rows.maxOfOrNull { it.size } ?: 1)
            items.put(
                JSONObject()
                    .put("range", "$sheet!A1:$endColumn${rows.size.coerceAtLeast(1)}")
                    .put("majorDimension", "ROWS")
                    .put("values", rowArray)
            )
        }
        val updateBody = JSONObject()
            .put("valueInputOption", "RAW")
            .put("data", items)
        request(
            method = "POST",
            url = "https://sheets.googleapis.com/v4/spreadsheets/$SPREADSHEET_ID/values:batchUpdate",
            token = token,
            body = updateBody.toString()
        )
    }

    private fun request(method: String, url: String, token: String, body: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30_000
            readTimeout = 60_000
            doInput = true
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let { s -> BufferedReader(InputStreamReader(s)).use { it.readText() } } ?: ""
        conn.disconnect()
        if (code == 401) throw UnauthorizedException()
        if (code !in 200..299) {
            val detail = try {
                JSONObject(text).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            } catch (_: Exception) { null }
            throw IllegalStateException("Google Sheet yazma hatası ($code): ${detail ?: text.take(250)}")
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
