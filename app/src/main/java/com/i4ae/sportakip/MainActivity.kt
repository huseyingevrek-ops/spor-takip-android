package com.i4ae.sportakip

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.setPadding
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var steps: TextView
    private lateinit var lastSync: TextView
    private lateinit var autoSwitch: Switch
    private lateinit var freqSpinner: Spinner
    private lateinit var hourSpinner: Spinner
    private lateinit var driveButton: Button

    private val readSteps = HealthPermission.getReadPermission(StepsRecord::class)
    private val bgRead = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { refresh() }

    private val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            AppPrefs.setTreeUri(this, uri)
            SyncScheduler.apply(this)
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "Spor Takip"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Health Connect → Google Drive"
            textSize = 15f
            setPadding(0, dp(2), 0, dp(18))
        })

        status = TextView(this).apply { textSize = 16f }
        steps = TextView(this).apply {
            textSize = 32f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(18), 0, dp(6))
        }
        lastSync = TextView(this).apply { textSize = 14f; setPadding(0, 0, 0, dp(14)) }
        root.addView(status)
        root.addView(steps)
        root.addView(lastSync)

        root.addView(Button(this).apply {
            text = "Health Connect izinlerini ver"
            setOnClickListener { requestHealthPermissions() }
        })

        driveButton = Button(this).apply {
            text = "Google Drive klasörünü seç"
            setOnClickListener { folderLauncher.launch(AppPrefs.treeUri(this@MainActivity)) }
        }
        root.addView(driveButton)

        root.addView(Button(this).apply {
            text = "Şimdi senkronize et"
            setOnClickListener { manualSync() }
        })

        root.addView(horizontalLabel("Otomatik senkronizasyon", Switch(this).also { sw ->
            autoSwitch = sw
            sw.isChecked = AppPrefs.autoSync(this)
            sw.setOnCheckedChangeListener { _, checked ->
                AppPrefs.setAutoSync(this, checked)
                SyncScheduler.apply(this)
            }
        }))

        val frequencies = listOf("Günde 1 kez", "Günde 2 kez", "Günde 4 kez", "Günde 6 kez", "Günde 8 kez")
        freqSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, frequencies)
            val values = listOf(1, 2, 4, 6, 8)
            setSelection(values.indexOf(AppPrefs.frequencyPerDay(this@MainActivity)).coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    AppPrefs.setFrequencyPerDay(this@MainActivity, values[position])
                    SyncScheduler.apply(this@MainActivity)
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }
        root.addView(horizontalLabel("Sıklık", freqSpinner))

        val hours = (0..23).map { "%02d:00".format(it) }
        hourSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, hours)
            setSelection(AppPrefs.startHour(this@MainActivity))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    AppPrefs.setStartHour(this@MainActivity, position)
                    SyncScheduler.apply(this@MainActivity)
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }
        root.addView(horizontalLabel("İlk senkronizasyon saati", hourSpinner))

        root.addView(Button(this).apply {
            text = "Drive bağlantısını kaldır"
            setOnClickListener {
                AppPrefs.treeUri(this@MainActivity)?.let { uri ->
                    try { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
                }
                AppPrefs.setTreeUri(this@MainActivity, null)
                SyncScheduler.apply(this@MainActivity)
                refresh()
            }
        })

        root.addView(TextView(this).apply {
            text = "Not: Android, pil tasarrufu nedeniyle otomatik senkronizasyonu seçilen saatten birkaç dakika geciktirebilir."
            textSize = 12f
            setPadding(0, dp(14), 0, 0)
        })
        return scroll
    }

    private fun horizontalLabel(label: String, control: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(4))
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 15f
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(control)
    }

    private fun requestHealthPermissions() {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
            return
        }
        lifecycleScope.launch {
            val client = HealthConnectClient.getOrCreate(this@MainActivity)
            val requested = mutableSetOf(readSteps)
            if (client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) {
                requested += bgRead
            }
            permissionLauncher.launch(requested)
        }
    }

    private fun manualSync() {
        lifecycleScope.launch {
            status.text = "Senkronize ediliyor…"
            val result = try { HealthSyncEngine.sync(this@MainActivity) } catch (e: Exception) {
                HealthSyncEngine.Result(false, e.message ?: "Hata")
            }
            Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    private fun refresh() {
        val sdk = HealthConnectClient.getSdkStatus(this)
        val sdkText = when (sdk) {
            HealthConnectClient.SDK_AVAILABLE -> "Health Connect: hazır"
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Health Connect: güncelleme gerekli"
            else -> "Health Connect: kullanılamıyor"
        }
        val driveText = if (AppPrefs.treeUri(this) == null) "Drive: klasör seçilmedi" else "Drive: bağlı (${HealthSyncEngine.AUTO_FILE})"
        status.text = "$sdkText\n$driveText"
        driveButton.text = if (AppPrefs.treeUri(this) == null) "Google Drive klasörünü seç" else "Drive klasörünü değiştir"
        autoSwitch.isChecked = AppPrefs.autoSync(this)
        lastSync.text = "Son senkronizasyon: ${AppPrefs.lastSync(this)} — ${AppPrefs.lastStatus(this)}"

        if (sdk == HealthConnectClient.SDK_AVAILABLE) {
            lifecycleScope.launch {
                steps.text = try { "Bugün: ${HealthSyncEngine.todaySteps(this@MainActivity)} adım" } catch (_: Exception) { "Bugün: izin gerekli" }
            }
        } else {
            steps.text = "Bugün: -"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
