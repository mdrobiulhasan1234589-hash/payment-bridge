package com.drltour.paymentbridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.content.Intent
import android.widget.Button
import android.widget.EditText
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

    private val PERMISSION_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupListeners()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshUiState()

        // Auto-start the foreground service if monitoring is enabled
        if (Prefs.isMonitoringEnabled(this)) {
            PaymentMonitorService.start(this)
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

        btnOpenAccess.setOnClickListener {
            openNotificationAccessSettings()
        }

        btnTestConnection.setOnClickListener {
            runConnectionTest()
        }

        btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        btnLogs.setOnClickListener {
            showLogsDialog()
        }

        btnHistory.setOnClickListener {
            showHistoryDialog()
        }
    }

    /**
     * Refresh all UI state — call from onResume and after button actions.
     */
    private fun refreshUiState() {
        // Notification Access
        val hasAccess = isNotificationAccessGranted()
        tvAccessStatus.text = if (hasAccess) "CONNECTED ✓" else "NOT CONNECTED"
        tvAccessStatus.setTextColor(getColor(if (hasAccess) R.color.status_ok else R.color.status_error))

        // Service (monitoring) status
        val monitoring = Prefs.isMonitoringEnabled(this)
        val serviceRunning = hasAccess && monitoring
        tvServiceStatus.text = if (serviceRunning) "RUNNING ✓" else "STOPPED"
        tvServiceStatus.setTextColor(getColor(if (serviceRunning) R.color.status_ok else R.color.status_error))

        // Backend status (cached result — Test button updates this)
        val lastBackend = Prefs.getBackendUrl(this)
        tvBackendStatus.text = if (lastBackend.isNotBlank()) "CONFIGURED ✓" else "NOT CONFIGURED"
        tvBackendStatus.setTextColor(getColor(if (lastBackend.isNotBlank()) R.color.status_ok else R.color.status_warn))

        // Enable/disable start/stop buttons based on state
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
            } catch (_: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = android.net.Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (_: Exception) {
                    // Give up silently
                }
            }
        }
    }

    private fun runConnectionTest() {
        tvBackendStatus.text = "TESTING..."
        tvBackendStatus.setTextColor(getColor(R.color.status_warn))

        activityScope.launch {
            val result = ApiClient.testConnection(this@MainActivity)
            withContext(Dispatchers.Main) {
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
                refreshUiState()
                showToast("Settings saved ✓")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLogsDialog() {
        val logs = LogManager.getList(this)
        val message = if (logs.isEmpty()) "No logs yet" else logs.joinToString("\n\n")

        AlertDialog.Builder(this)
            .setTitle("Logs")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear") { _, _ ->
                LogManager.clear(this)
                showToast("Logs cleared")
            }
            .show()
    }

    private fun showHistoryDialog() {
        AlertDialog.Builder(this)
            .setTitle("History")
            .setMessage(
                "Payment history view is available in the Logs screen.\n\n" +
                        "Every parsed notification is logged with method, amount, sender, and TrxID."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showToast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * Request POST_NOTIFICATIONS permission on Android 13+.
     * Required for the foreground service notification to be visible.
     */
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
                    PERMISSION_REQUEST_CODE
                )
            }
        }
    }
}
