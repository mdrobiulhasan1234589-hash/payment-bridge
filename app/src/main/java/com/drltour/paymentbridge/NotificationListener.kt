package com.drltour.paymentbridge

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotificationListener : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val recentTrxIds = LinkedHashMap<String, Long>()
    private val TRX_MEMORY_WINDOW_MS = 10 * 60 * 1000L
    private val MAX_RECENT = 100

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            LogManager.add(applicationContext, "INFO", "🔌 NotificationListener CONNECTED")
        } catch (_: Exception) { }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            LogManager.add(applicationContext, "INFO", "🔌 NotificationListener DISCONNECTED")
        } catch (_: Exception) { }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            if (sbn == null) return

            val packageName = sbn.packageName ?: return

            if (!isPaymentSourcePackage(packageName)) return

            if (!Prefs.isMonitoringEnabled(applicationContext)) {
                LogManager.add(applicationContext, "DEBUG", "⚠️ Monitoring disabled")
                return
            }

            LogManager.add(applicationContext, "DEBUG", "📩 Notification from: $packageName")

            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return

            val title = safeString(extras.getCharSequence(Notification.EXTRA_TITLE))
            val text = safeString(extras.getCharSequence(Notification.EXTRA_TEXT))
            val bigText = safeString(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
            val subText = safeString(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
            val infoText = safeString(extras.getCharSequence(Notification.EXTRA_INFO_TEXT))
            val summaryText = safeString(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
            val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.joinToString(" ") { safeString(it) } ?: ""

            val combinedText = listOf(text, bigText, subText, infoText, summaryText, textLines)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { text }

            LogManager.add(
                applicationContext,
                "DEBUG",
                "📝 Title: $title | Text: ${combinedText.take(200)}"
            )

            if (combinedText.isBlank() && title.isBlank()) return

            val payment = PaymentParser.parse(title, combinedText) { logMsg ->
                LogManager.add(applicationContext, "DEBUG", logMsg)
            }

            if (payment == null) {
                LogManager.add(applicationContext, "DEBUG", "❌ Parser returned NULL")
                return
            }

            val now = System.currentTimeMillis()
            pruneOldTrxIds(now)

            synchronized(recentTrxIds) {
                val lastSeen = recentTrxIds[payment.trxId]
                if (lastSeen != null && (now - lastSeen) < TRX_MEMORY_WINDOW_MS) {
                    LogManager.add(
                        applicationContext,
                        "DUPLICATE",
                        "Ignored duplicate: ${PaymentParser.maskTrxId(payment.trxId)}"
                    )
                    return
                }
                recentTrxIds[payment.trxId] = now
            }

            LogManager.add(
                applicationContext,
                "PARSED",
                "${payment.method.uppercase()} ৳${payment.amount} from " +
                        "${PaymentParser.maskPhone(payment.senderPhone)} " +
                        "TrxID ${PaymentParser.maskTrxId(payment.trxId)}"
            )

            serviceScope.launch {
                val result = ApiClient.sendPayment(applicationContext, payment)

                if (result.success) {
                    LogManager.add(
                        applicationContext,
                        "SENT",
                        "Server: ${result.status} — ${PaymentParser.maskTrxId(payment.trxId)}"
                    )
                    val summary = buildSummary(payment, result.status)
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
            try {
                LogManager.add(applicationContext, "ERROR", "Listener error: ${e.message}")
            } catch (_: Exception) { }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) { }

    private fun safeString(cs: CharSequence?): String {
        return try {
            cs?.toString()?.trim() ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Payment apps AND Messages apps — full text is more reliable.
     */
    private fun isPaymentSourcePackage(pkg: String): Boolean {
        return when (pkg) {
            // bKash
            "com.bKash.customerapp" -> true
            "com.bkash.customerapp" -> true

            // Nagad
            "com.konasl.nagad" -> true
            "com.nagad.app" -> true

            // Rocket
            "com.dbbl.mbs.apps.rocket" -> true
            "com.dbbl.mbs" -> true

            // Messages / SMS apps
            "com.google.android.apps.messaging" -> true
            "com.android.mms" -> true
            "com.android.messaging" -> true
            "com.samsung.android.messaging" -> true
            "com.transsion.smartmessage" -> true
            "com.transsion.messaging" -> true
            "com.transsion.smart.chat" -> true
            "com.android.mms.service" -> true

            else -> false
        }
    }

    private fun pruneOldTrxIds(now: Long) {
        synchronized(recentTrxIds) {
            val iterator = recentTrxIds.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if ((now - entry.value) > TRX_MEMORY_WINDOW_MS) {
                    iterator.remove()
                }
            }
            while (recentTrxIds.size > MAX_RECENT) {
                val firstKey = recentTrxIds.keys.firstOrNull() ?: break
                recentTrxIds.remove(firstKey)
            }
        }
    }

    private fun buildSummary(
        payment: PaymentParser.PaymentData,
        status: String
    ): String {
        return try {
            val timeFormat = java.text.SimpleDateFormat("hh:mm:ss a", java.util.Locale.US)
            val maskedPhone = PaymentParser.maskPhone(payment.senderPhone)
            val maskedTrx = PaymentParser.maskTrxId(payment.trxId)
            "$status|${payment.method}|${payment.amount}|$maskedPhone|$maskedTrx|${timeFormat.format(java.util.Date())}"
        } catch (_: Exception) {
            ""
        }
    }
}
