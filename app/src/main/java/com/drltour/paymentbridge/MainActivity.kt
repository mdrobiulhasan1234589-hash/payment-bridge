package com.drltour.paymentbridge

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.content.Intent
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var tvServiceStatus: TextView
    private lateinit var tvAccessStatus: TextView
    private lateinit var tvBackendStatus: TextView
    private lateinit var tvLastPayment: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnOpenAccess: Button
    private lateinit var btnTestConnection: Button
    private lateinit var btnSettings: Button
    private lateinit var btnLogs: Button
    private lateinit var btnHistory: Button

    private val SMS_PERMISSION_CODE = 200
    private val NOTIF_PERMISSION_CODE = 201
    private var lastTestTime = 0L
    private val TEST_CACHE_MS = 60_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupListeners()
        requestNotificationPermissionIfNeeded()
        requestSmsPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshUiState()

        if (Prefs.isMonitoringEnabled(this)) {
            PaymentMonitorService.start(this)
        }

        val now = System.currentTimeMillis()
        val lastCheck = Prefs.getBackendLastCheck(this)
        if (Prefs.getBackendUrl(this).isNotBlank() &&
            Prefs.getBridgeSecret(this).isNotBlank() &&
            (now - lastCheck) > TEST_CACHE_MS
        ) {
            runConnectionTestSilent()
        }
    }

    private fun bindViews() {
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        tvAccessStatus = findViewById(R.id.tvAccessStatus)
        tvBackendStatus = findViewById(R.id.tvBackendStatus)
        tvLastPayment = findViewById(R.id.tvLastPayment)
        btnStart = findViewById(R.id.btnStartMonitoring)
        btnStop = findViewById(R.id.btnStopMonitoring)
        btnOpenAccess = findViewById(R.id.btnOpenAccess)
        btnTestConnection = findViewById(R.id.btnTestConnection)
        btnSettings = findViewById(R.id.btnSettings)
        btnLogs = findViewById(R.id.btnLogs)
        btnHistory = findViewById(R.id.btnHistory)
    }

    private fun setupListeners() {
        btnStart.setOnClickListener {
            if (!hasSmsPermission()) {
                showToast("Please grant SMS permission first")
                requestSmsPermissionIfNeeded()
                return@setOnClickListener
            }
            Prefs.setMonitoringEnabled(this, true)
            PaymentMonitorService.start(this)
            LogManager.add(this, "INFO", "Monitoring started via foreground service")
            refreshUiState()
        }

        btnStop.setOnClickListener {
            Prefs.setMonitoringEnabled(this, false)
            PaymentMonitorService.stop(this)
            LogManager.add(this, "INFO", "Monitoring stopped by user")
            refreshUiState()
        }

        btnOpenAccess.setOnClickListener { openNotificationAccessSettings() }
        btnTestConnection.setOnClickListener { runConnectionTest() }
        btnSettings.setOnClickListener { showSettingsDialog() }
        btnLogs.setOnClickListener { showLogsDialog() }
        btnHistory.setOnClickListener { showHistoryDialog() }
    }

    private fun refreshUiState() {
        val hasAccess = isNotificationAccessGranted()
        tvAccessStatus.text = if (hasAccess) "CONNECTED ✓" else "NOT CONNECTED"
        tvAccessStatus.setTextColor(getColor(if (hasAccess) R.color.status_ok else R.color.status_error))

        val monitoring = Prefs.isMonitoringEnabled(this)
        val serviceRunning = monitoring && Prefs.getBackendUrl(this).isNotBlank()
        tvServiceStatus.text = if (serviceRunning) "RUNNING ✓" else "STOPPED"
        tvServiceStatus.setTextColor(getColor(if (serviceRunning) R.color.status_ok else R.color.status_error))

        val hasUrl = Prefs.getBackendUrl(this).isNotBlank()
        val hasSecret = Prefs.getBridgeSecret(this).isNotBlank()
        if (hasUrl && hasSecret) {
            if (Prefs.isBackendConnected(this)) {
                tvBackendStatus.text = "CONNECTED ✓"
                tvBackendStatus.setTextColor(getColor(R.color.status_ok))
            } else {
                val lastCheck = Prefs.getBackendLastCheck(this)
                if (lastCheck > 0) {
                    tvBackendStatus.text = "FAILED"
                    tvBackendStatus.setTextColor(getColor(R.color.status_error))
                } else {
                    tvBackendStatus.text = "NOT TESTED"
                    tvBackendStatus.setTextColor(getColor(R.color.status_warn))
                }
            }
        } else {
            tvBackendStatus.text = "NOT CONFIGURED"
            tvBackendStatus.setTextColor(getColor(R.color.status_warn))
        }

        btnStart.isEnabled = !monitoring
        btnStop.isEnabled = monitoring

        refreshLastPayment()
    }

    private fun refreshLastPayment() {
        val summary = Prefs.getLastPaymentSummary(this)
        if (summary.isBlank()) {
            tvLastPayment.text = "No payments yet"
            return
        }
        try {
            val parts = summary.split("|")
            if (parts.size >= 5) {
                val status = parts[0]
                val method = parts[1].uppercase()
                val amount = parts[2]
                val phone = parts[3]
                val trx = parts[4]
                tvLastPayment.text = buildString {
                    append("Method: $method\n")
                    append("Amount: ৳$amount\n")
                    append("Sender: $phone\n")
                    append("TrxID: $trx\n")
                    append("Status: $status")
                }
            } else {
                tvLastPayment.text = "No payments yet"
            }
        } catch (_: Exception) {
            tvLastPayment.text = "No payments yet"
        }
    }

    private fun hasSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_SMS
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestSmsPermissionIfNeeded() {
        if (!hasSmsPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS
                ),
                SMS_PERMISSION_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SMS_PERMISSION_CODE) {
            if (hasSmsPermission()) {
                showToast("SMS permission granted ✓")
            } else {
                showToast("SMS permission is required")
            }
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        return try {
            val enabled = NotificationManagerCompat.getEnabledListenerPackages(this)
            enabled.contains(packageName)
        } catch (_: Exception) {
            false
        }
    }

    private fun openNotificationAccessSettings() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                startActivity(intent)
            } catch (_: Exception) { }
        }
    }

    private fun runConnectionTest() {
        tvBackendStatus.text = "TESTING..."
        tvBackendStatus.setTextColor(getColor(R.color.status_warn))

        activityScope.launch {
            val result = ApiClient.testConnection(this@MainActivity)
            withContext(Dispatchers.Main) {
                Prefs.setBackendLastCheck(this@MainActivity, System.currentTimeMillis())
                Prefs.setBackendConnected(this@MainActivity, result.success)

                if (result.success) {
                    tvBackendStatus.text = "CONNECTED ✓"
                    tvBackendStatus.setTextColor(getColor(R.color.status_ok))
                    showToast("Backend reachable ✓")
                } else {
                    tvBackendStatus.text = "FAILED: ${result.status}"
                    tvBackendStatus.setTextColor(getColor(R.color.status_error))
                    showToast("Backend error: ${result.message}")
                }
            }
        }
    }

    private fun runConnectionTestSilent() {
        activityScope.launch {
            val result = ApiClient.testConnection(this@MainActivity)
            withContext(Dispatchers.Main) {
                val wasConnected = Prefs.isBackendConnected(this@MainActivity)
                Prefs.setBackendLastCheck(this@MainActivity, System.currentTimeMillis())
                Prefs.setBackendConnected(this@MainActivity, result.success)

                if (result.success != wasConnected) {
                    if (result.success) {
                        tvBackendStatus.text = "CONNECTED ✓"
                        tvBackendStatus.setTextColor(getColor(R.color.status_ok))
                    } else {
                        tvBackendStatus.text = "FAILED"
                        tvBackendStatus.setTextColor(getColor(R.color.status_error))
                    }
                }
            }
        }
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etUrl = view.findViewById<EditText>(R.id.etBackendUrl)
        val etSecret = view.findViewById<EditText>(R.id.etBridgeSecret)

        etUrl.setText(Prefs.getBackendUrl(this))
        etSecret.setText(Prefs.getBridgeSecret(this))

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val url = etUrl.text.toString().trim()
                val secret = etSecret.text.toString().trim()

                if (url.isBlank()) {
                    showToast("Backend URL cannot be empty")
                    return@setPositiveButton
                }
                if (!url.startsWith("https://")) {
                    showToast("URL must start with https://")
                    return@setPositiveButton
                }

                Prefs.setBackendUrl(this, url)
                Prefs.setBridgeSecret(this, secret)
                LogManager.add(this, "INFO", "Settings updated")

                Prefs.setBackendConnected(this, false)
                Prefs.setBackendLastCheck(this, 0L)
                runConnectionTestSilent()

                refreshUiState()
                showToast("Settings saved ✓")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLogsDialog() {
        val logs = LogManager.getList(this)
        val message = if (logs.isEmpty()) "No logs yet" else logs.joinToString("\n\n")

        val scrollView = ScrollView(this).apply {
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#0A0A1A"))
        }

        val textView = TextView(this).apply {
            text = message
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 12f
            setLineSpacing(8f, 1f)
            setPadding(10, 10, 10, 10)
            setTextIsSelectable(true)
        }

        scrollView.addView(
            textView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle("📋 Logs")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear") { _, _ ->
                LogManager.clear(this)
                showToast("Logs cleared")
            }
            .create()

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.background_dark)
            val titleId = resources.getIdentifier("alertTitle", "id", "android")
            if (titleId > 0) {
                dialog.findViewById<TextView>(titleId)?.setTextColor(Color.WHITE)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.parseColor("#3355FF"))
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(Color.parseColor("#FF9800"))
        }

        dialog.show()
    }

    private fun showHistoryDialog() {
        AlertDialog.Builder(this)
            .setTitle("History")
            .setMessage("SMS history is available in the Logs screen.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showToast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIF_PERMISSION_CODE
                )
            }
        }
    }
}
