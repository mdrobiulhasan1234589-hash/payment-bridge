package com.drltour.paymentbridge

import android.content.Context
import android.content.SharedPreferences

/**
 * Prefs — Simple SharedPreferences wrapper for storing app settings.
 * Stores: backend URL, bridge secret, monitoring enabled/disabled.
 */
object Prefs {

    private const val PREF_NAME = "payment_bridge_prefs"
    private const val KEY_BACKEND_URL = "backend_url"
    private const val KEY_BRIDGE_SECRET = "bridge_secret"
    private const val KEY_MONITORING = "monitoring_enabled"
    private const val KEY_LAST_PAYMENT = "last_payment_summary"

    private fun get(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getBackendUrl(context: Context): String {
        return get(context).getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL) ?: DEFAULT_BACKEND_URL
    }

    fun setBackendUrl(context: Context, url: String) {
        get(context).edit().putString(KEY_BACKEND_URL, url.trim()).apply()
    }

    fun getBridgeSecret(context: Context): String {
        return get(context).getString(KEY_BRIDGE_SECRET, "") ?: ""
    }

    fun setBridgeSecret(context: Context, secret: String) {
        get(context).edit().putString(KEY_BRIDGE_SECRET, secret.trim()).apply()
    }

    fun isMonitoringEnabled(context: Context): Boolean {
        return get(context).getBoolean(KEY_MONITORING, true)
    }

    fun setMonitoringEnabled(context: Context, enabled: Boolean) {
        get(context).edit().putBoolean(KEY_MONITORING, enabled).apply()
    }

    fun getLastPaymentSummary(context: Context): String {
        return get(context).getString(KEY_LAST_PAYMENT, "") ?: ""
    }

    fun setLastPaymentSummary(context: Context, summary: String) {
        get(context).edit().putString(KEY_LAST_PAYMENT, summary).apply()
    }

    // ⚠️ Default backend URL — user Settings থেকে পরিবর্তন করতে পারবে
    // এটা আপনার Supabase Edge Function-এর URL
    const val DEFAULT_BACKEND_URL =
        "https://ekecrhimsolyufupstzv.supabase.co/functions/v1/payment-bridge"
}
