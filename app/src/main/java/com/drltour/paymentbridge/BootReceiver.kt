package com.drltour.paymentbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * BootReceiver — Auto-starts PaymentMonitorService after the phone restarts.
 * Also handles the alarm-based restart used by PaymentMonitorService when
 * the service is killed by the system.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            when (intent?.action) {
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                "android.intent.action.QUICKBOOT_POWERON",
                "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                    if (Prefs.isMonitoringEnabled(context)) {
                        PaymentMonitorService.start(context)
                    }
                }

                Intent.ACTION_MY_PACKAGE_REPLACED -> {
                    // App was updated — restart monitoring if it was on
                    if (Prefs.isMonitoringEnabled(context)) {
                        PaymentMonitorService.start(context)
                    }
                }
            }
        } catch (_: Exception) { }
    }
}
