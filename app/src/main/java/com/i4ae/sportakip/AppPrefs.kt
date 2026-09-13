package com.i4ae.sportakip

import android.content.Context

object AppPrefs {
    private const val PREFS = "spor_takip"
    private const val KEY_AUTO = "auto_sync"
    private const val KEY_FREQ = "freq_per_day"
    private const val KEY_START_HOUR = "start_hour"
    private const val KEY_LAST_SYNC = "last_sync"
    private const val KEY_LAST_STATUS = "last_status"
    private const val KEY_HISTORY_IMPORTED = "history_imported_v3"
    private const val KEY_GOOGLE_ACCOUNT = "google_account"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun googleAccount(context: Context): String? = prefs(context).getString(KEY_GOOGLE_ACCOUNT, null)
    fun setGoogleAccount(context: Context, value: String?) {
        prefs(context).edit().apply {
            if (value.isNullOrBlank()) remove(KEY_GOOGLE_ACCOUNT) else putString(KEY_GOOGLE_ACCOUNT, value)
        }.apply()
    }

    fun autoSync(context: Context) = prefs(context).getBoolean(KEY_AUTO, true)
    fun setAutoSync(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_AUTO, value).apply()

    fun frequencyPerDay(context: Context) = prefs(context).getInt(KEY_FREQ, 4)
    fun setFrequencyPerDay(context: Context, value: Int) = prefs(context).edit().putInt(KEY_FREQ, value).apply()

    fun startHour(context: Context) = prefs(context).getInt(KEY_START_HOUR, 6)
    fun setStartHour(context: Context, value: Int) = prefs(context).edit().putInt(KEY_START_HOUR, value).apply()

    fun lastSync(context: Context) = prefs(context).getString(KEY_LAST_SYNC, "Henüz yok") ?: "Henüz yok"
    fun lastStatus(context: Context) = prefs(context).getString(KEY_LAST_STATUS, "-") ?: "-"
    fun setLastResult(context: Context, sync: String, status: String) {
        prefs(context).edit().putString(KEY_LAST_SYNC, sync).putString(KEY_LAST_STATUS, status).apply()
    }

    fun historyImported(context: Context) = prefs(context).getBoolean(KEY_HISTORY_IMPORTED, false)
    fun setHistoryImported(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_HISTORY_IMPORTED, value).apply()
}
