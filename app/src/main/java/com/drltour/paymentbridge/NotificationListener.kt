package com.drltour.paymentbridge

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * NotificationListener — Receives payment notifications from bKash / Nagad / Rocket.
 *
 * This service runs whenever the OS has granted Notification Access.
 * It filters notifications, parses them, and forwards valid payments to the backend.
 */
class NotificationListener : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Track recently processed TrxIDs to avoid double-processing
    // (server-side also enforces this, but this prevents redundant network calls)
    private val recentTrxIds = LinkedHashMap<String, Long>()
    private val TRX_MEMORY_WINDOW_MS = 10 * 60 * 1000L // 10 minutes
    private val MAX_RECENT = 100

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            if (sbn == null) return

            // Only handle notifications from known payment sources
            val packageName = sbn.packageName ?: return
            if (!isPaymentSourcePackage(packageName)) return

            // Check if monitoring is enabled in settings
            if (!Prefs.isMonitoringEnabled(applicationContext)) return

            val extras = sbn.notification?.extras ?: return

            // Extract notification title + text
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.joinToString(" ") { it.toString() } ?: ""

            // Combine all possible text sources (big text usually has the full message)
            val combinedText = listOf(text, bigText, textLines)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { text }

            if (combinedText.isBlank() && title.isBlank()) return

            // Parse the notification
            val payment = PaymentParser.parse(title, combinedText) ?: run {
                // Not a valid payment notification — silently ignore
                return
            }

            // Local duplicate check
            val now = System.currentTimeMillis()
            pruneOldTrxIds(now)

            synchronized(recentTrxIds) {
                val lastSeen = recentTrxIds[payment.trxId]
                if (lastSeen != null && (now - lastSeen) < TRX_MEMORY_WINDOW_MS) {
                    // Duplicate — ignore
                    LogManager.add(
                        applicationContext,
                        "DUPLICATE",
                        "Ignored duplicate TrxID ${PaymentParser.maskTrxId(payment.trxId)}"
                    )
                    return
                }
                recentTrxIds[payment.trxId] = now
            }

            // Log parsed payment
            LogManager.add(
                applicationContext,
                "PARSED",
                "${payment.method.uppercase()} ৳${payment.amount} from " +
                        "${PaymentParser.maskPhone(payment.senderPhone)} " +
                        "TrxID ${PaymentParser.maskTrxId(payment.trxId)}"
            )

            // Send to backend (background)
            serviceScope.launch {
                val result = ApiClient.sendPayment(applicationContext, payment)

                if (result.success) {
                    LogManager.add(
                        applicationContext,
                        "SENT",
                        "Server: ${result.status} — ${PaymentParser.maskTrxId(payment.trxId)}"
                    )
                    // Save last payment summary for UI
                    val summary = buildLastPaymentSummary(payment, result.status)
                    Prefs.setLastPaymentSummary(applicationContext, summary)
                } else {
                    LogManager.add(
                        applicationContext,
                        "ERROR",
                        "Send failed: ${result.status} — ${result.message}"
                    )
                }
            }

        } catch (e: Exception) {
            // Never crash from a bad notification
            try {
                LogManager.add(
                    applicationContext,
                    "ERROR",
                    "Listener error: ${e.message}"
                )
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No-op — we don't care about removed notifications
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            LogManager.add(applicationContext, "INFO", "Notification access connected")
        } catch (_: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            LogManager.add(applicationContext, "INFO", "Notification access disconnected")
        } catch (_: Exception) {
        }
    }

    /**
     * Checks whether a package name belongs to a known payment provider.
     * Package names are the source of truth — title text can be faked.
     */
    private fun isPaymentSourcePackage(pkg: String): Boolean {
        return when (pkg) {
            // bKash
            "com.bKash.customerapp",
            "com.bkash.customerapp",
            // Nagad
            "com.konasl.nagad",
            "com.nagad.app",
            // Rocket (DBBL)
            "com.dbbl.mbs.apps.rocket",
            "com.dbbl.mbs",
            // Generic SMS apps — DO NOT include. We only listen to payment apps.
            else -> false
        }
    }

    /**
     * Removes TrxIDs older than the memory window from the cache.
     */
    private fun pruneOldTrxIds(now: Long) {
        synchronized(recentTrxIds) {
            val iterator = recentTrxIds.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if ((now - entry.value) > TRX_MEMORY_WINDOW_MS) {
                    iterator.remove()
                }
            }
            // Cap the map size
            while (recentTrxIds.size > MAX_RECENT) {
                val firstKey = recentTrxIds.keys.firstOrNull() ?: break
                recentTrxIds.remove(firstKey)
            }
        }
    }

    private fun buildLastPaymentSummary(
        payment: PaymentParser.PaymentData,
        status: String
    ): String {
        return try {
            val maskedPhone = PaymentParser.maskPhone(payment.senderPhone)
            val maskedTrx = PaymentParser.maskTrxId(payment.trxId)
            "$status|${payment.method}|${payment.amount}|$maskedPhone|$maskedTrx|${System.currentTimeMillis()}"
        } catch (_: Exception) {
            ""
        }
    }
}
