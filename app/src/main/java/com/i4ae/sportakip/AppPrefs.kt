package com.i4ae.sportakip

import android.content.Context
import android.net.Uri

object AppPrefs {
    private const val PREFS = "spor_takip"
    private const val KEY_TREE_URI = "tree_uri"
    private const val KEY_AUTO = "auto_sync"
    private const val KEY_FREQ = "freq_per_day"
    private const val KEY_START_HOUR = "start_hour"
    private const val KEY_LAST_SYNC = "last_sync"
    private const val KEY_LAST_STATUS = "last_status"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun treeUri(context: Context): Uri? = prefs(context).getString(KEY_TREE_URI, null)?.let(Uri::parse)
    fun setTreeUri(context: Context, uri: Uri?) {
        prefs(context).edit().apply {
            if (uri == null) remove(KEY_TREE_URI) else putString(KEY_TREE_URI, uri.toString())
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
}
