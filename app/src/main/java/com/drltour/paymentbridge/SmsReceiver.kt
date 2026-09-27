package com.drltour.paymentbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SmsReceiver — System-level SMS BroadcastReceiver.
 *
 * Android automatically delivers incoming SMS to this receiver even when the
 * app is closed. This is the most reliable way to catch bKash/Nagad/Rocket
 * payment notifications on Android 13+ / 14 / 15 / 16.
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val recentTrxIds = LinkedHashMap<String, Long>()
        private const val TRX_MEMORY_WINDOW_MS = 5 * 60 * 1000L
        private const val MAX_RECENT = 100

        /**
         * Called by both the manifest receiver and the runtime receiver.
         * Public so NotificationListener can share the duplicate check.
         */
        fun handleSms(context: Context, fullBody: String) {
            try {
                if (!Prefs.isMonitoringEnabled(context)) {
                    LogManager.add(context, "DEBUG", "SMS received but monitoring disabled")
                    return
                }

                if (fullBody.isBlank()) return

                LogManager.add(
                    context,
                    "DEBUG",
                    "📨 SMS received (len=${fullBody.length}): ${fullBody.take(200)}"
                )

                val payment = PaymentParser.parse("", fullBody) { logMsg ->
                    LogManager.add(context, "DEBUG", logMsg)
                }

                if (payment == null) {
                    LogManager.add(context, "DEBUG", "❌ SMS Parser returned NULL")
                    return
                }

                // Duplicate check (shared with NotificationListener — same map)
                val now = System.currentTimeMillis()
                pruneOldTrxIds(now)

                synchronized(recentTrxIds) {
                    val lastSeen = recentTrxIds[payment.trxId]
                    if (lastSeen != null && (now - lastSeen) < TRX_MEMORY_WINDOW_MS) {
                        LogManager.add(
                            context,
                            "DUPLICATE",
                            "Ignored duplicate TrxID (SMS): ${PaymentParser.maskTrxId(payment.trxId)}"
                        )
                        return
                    }
                    recentTrxIds[payment.trxId] = now
                }

                LogManager.add(
                    context,
                    "PARSED",
                    "SMS ${payment.method.uppercase()} ৳${payment.amount} from " +
                            "${PaymentParser.maskPhone(payment.senderPhone)} " +
                            "TrxID ${PaymentParser.maskTrxId(payment.trxId)}"
                )

                scope.launch {
                    val result = ApiClient.sendPayment(context, payment)

                    if (result.success) {
                        LogManager.add(
                            context,
                            "SENT",
                            "SMS → Server: ${result.status} — ${PaymentParser.maskTrxId(payment.trxId)}"
                        )
                        val summary = buildSummary(payment, result.status)
                        Prefs.setLastPaymentSummary(context, summary)
                    } else {
                        LogManager.add(
                            context,
                            "ERROR",
                            "SMS send failed: ${result.status} — ${result.message}"
                        )
                    }
                }
            } catch (e: Exception) {
                try {
                    LogManager.add(context, "ERROR", "SmsReceiver handle error: ${e.message}")
                } catch (_: Exception) { }
            }
        }

        /**
         * Shared duplicate check entry — used by NotificationListener too.
         * Returns true if this TrxID should be skipped as duplicate.
         */
        fun isDuplicate(context: Context, trxId: String): Boolean {
            val now = System.currentTimeMillis()
            pruneOldTrxIds(now)
            synchronized(recentTrxIds) {
                val lastSeen = recentTrxIds[trxId]
                if (lastSeen != null && (now - lastSeen) < TRX_MEMORY_WINDOW_MS) {
                    return true
                }
                recentTrxIds[trxId] = now
                return false
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
                val timeFormat = SimpleDateFormat("hh:mm:ss a", Locale.US)
                val maskedPhone = PaymentParser.maskPhone(payment.senderPhone)
                val maskedTrx = PaymentParser.maskTrxId(payment.trxId)
                "$status|${payment.method}|${payment.amount}|$maskedPhone|$maskedTrx|${timeFormat.format(Date())}"
            } catch (_: Exception) {
                ""
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
            if (messages.isEmpty()) return

            val fullBodyBuilder = StringBuilder()
            for (msg in messages) {
                fullBodyBuilder.append(msg.displayMessageBody ?: "")
            }

            val fullBody = fullBodyBuilder.toString().trim()
            if (fullBody.isBlank()) return

            handleSms(context, fullBody)
        } catch (e: Exception) {
            try {
                LogManager.add(context, "ERROR", "SmsReceiver error: ${e.message}")
            } catch (_: Exception) { }
        }
    }
}
