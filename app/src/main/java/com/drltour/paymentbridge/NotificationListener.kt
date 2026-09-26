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
            LogManager.add(
                applicationContext,
                "DEBUG",
                "📩 Notification from: ${sbn?.packageName ?: "null"}"
            )

            if (sbn == null) return

            val packageName = sbn.packageName ?: return

            val isPaymentSource = isPaymentSourcePackage(packageName)
            LogManager.add(
                applicationContext,
                "DEBUG",
                "🔎 Package match: $packageName = $isPaymentSource"
            )

            if (!isPaymentSource) return

            if (!Prefs.isMonitoringEnabled(applicationContext)) {
                LogManager.add(applicationContext, "DEBUG", "⚠️ Monitoring disabled")
                return
            }

            val extras = sbn.notification?.extras ?: return

            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.joinToString(" ") { it.toString() } ?: ""

            val combinedText = listOf(text, bigText, textLines)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { text }

            LogManager.add(
                applicationContext,
                "DEBUG",
                "📝 Title: $title | Text: ${combinedText.take(120)}"
            )

            if (combinedText.isBlank() && title.isBlank()) return

            val payment = PaymentParser.parse(title, combinedText)

            if (payment == null) {
                LogManager.add(applicationContext, "DEBUG", "❌ Parser returned NULL — not a valid payment")
                return
            }

            LogManager.add(
                applicationContext,
                "DEBUG",
                "✅ Parser SUCCESS: ${payment.method} ৳${payment.amount} TrxID ${payment.trxId}"
            )

            val now = System.currentTimeMillis()
            pruneOldTrxIds(now)

            synchronized(recentTrxIds) {
                val lastSeen = recentTrxIds[payment.trxId]
                if (lastSeen != null && (now - lastSeen) < TRX_MEMORY_WINDOW_MS) {
                    LogManager.add(
                        applicationContext,
                        "DUPLICATE",
                        "Ignored duplicate TrxID ${PaymentParser.maskTrxId(payment.trxId)}"
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
            try {
                LogManager.add(
                    applicationContext,
                    "ERROR",
                    "Listener error: ${e.message}"
                )
            } catch (_: Exception) { }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) { }

    private fun isPaymentSourcePackage(pkg: String): Boolean {
        return when (pkg) {
            // ─── bKash ───
            "com.bKash.customerapp" -> true
            "com.bkash.customerapp" -> true

            // ─── Nagad ───
            "com.konasl.nagad" -> true
            "com.nagad.app" -> true

            // ─── Rocket (DBBL) ───
            "com.dbbl.mbs.apps.rocket" -> true
            "com.dbbl.mbs" -> true

            // ─── Google Messages ───
            "com.google.android.apps.messaging" -> true

            // ─── Infinix / Tecno / Transsion default SMS ───
            "com.transsion.smartmessage" -> true     // ⭐ আপনার ফোনের SMS app!
            "com.transsion.messaging" -> true
            "com.transsion.smart.chat" -> true

            // ─── Other SMS apps ───
            "com.android.mms" -> true
            "com.android.messaging" -> true
            "com.samsung.android.messaging" -> true
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
