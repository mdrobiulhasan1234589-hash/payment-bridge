package com.drltour.paymentbridge

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ApiClient — Sends parsed payment data to Supabase Edge Function.
 *
 * Sends HTTPS POST to the backend URL configured in Prefs.
 * Uses x-bridge-secret header for authentication.
 */
object ApiClient {

    private const val TAG = "PaymentBridge"

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Result of a send attempt.
     */
    data class Result(
        val success: Boolean,
        val status: String,
        val message: String
    )

    /**
     * Send a payment to the backend.
     * This runs on a background thread — safe to call from anywhere.
     */
    suspend fun sendPayment(
        context: Context,
        payment: PaymentParser.PaymentData
    ): Result = withContext(Dispatchers.IO) {

        val backendUrl = Prefs.getBackendUrl(context)
        val bridgeSecret = Prefs.getBridgeSecret(context)

        if (backendUrl.isBlank()) {
            return@withContext Result(
                success = false,
                status = "MISSING_URL",
                message = "Backend URL not configured in Settings"
            )
        }

        if (bridgeSecret.isBlank()) {
            return@withContext Result(
                success = false,
                status = "MISSING_SECRET",
                message = "Bridge Secret not configured in Settings"
            )
        }

        try {
            // Build JSON body
            val json = JSONObject().apply {
                put("sender_phone", payment.senderPhone)
                put("amount", payment.amount)
                put("method", payment.method)
                put("trx_id", payment.trxId)
                put("message", payment.originalMessage)
            }

            val body = json.toString().toRequestBody(JSON_MEDIA)

            val request = Request.Builder()
                .url(backendUrl)
                .addHeader("Content-Type", "application/json")
                .addHeader("x-bridge-secret", bridgeSecret)
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    LogManager.add(
                        context,
                        "ERROR",
                        "HTTP ${response.code} — ${responseBody.take(200)}"
                    )
                    return@withContext Result(
                        success = false,
                        status = "HTTP_${response.code}",
                        message = "Server returned ${response.code}"
                    )
                }

                // Parse server response
                try {
                    val jsonResp = JSONObject(responseBody)
                    val ok = jsonResp.optBoolean("ok", false)
                    val status = jsonResp.optString("status", "UNKNOWN")
                    val message = jsonResp.optString("message", "")

                    return@withContext Result(
                        success = ok,
                        status = status,
                        message = message.ifBlank { "Server responded: $status" }
                    )
                } catch (_: Exception) {
                    return@withContext Result(
                        success = true,
                        status = "OK",
                        message = "Sent (response not JSON)"
                    )
                }
            }
        } catch (e: Exception) {
            LogManager.add(context, "ERROR", "Network error: ${e.message}")
            return@withContext Result(
                success = false,
                status = "NETWORK_ERROR",
                message = e.message ?: "Network error"
            )
        }
    }

    /**
     * Test connection — sends a harmless ping-style request.
     * Returns true if backend responds (even with UNAUTHORIZED).
     */
    suspend fun testConnection(context: Context): Result = withContext(Dispatchers.IO) {
        val backendUrl = Prefs.getBackendUrl(context)
        val bridgeSecret = Prefs.getBridgeSecret(context)

        if (backendUrl.isBlank()) {
            return@withContext Result(false, "MISSING_URL", "Backend URL not set")
        }
        if (bridgeSecret.isBlank()) {
            return@withContext Result(false, "MISSING_SECRET", "Bridge Secret not set")
        }

        try {
            // Send a harmless invalid request — server will return INVALID_INPUT,
            // which confirms connection + auth are working.
            val json = JSONObject().apply {
                put("sender_phone", "")
                put("amount", 0)
                put("method", "")
                put("trx_id", "")
                put("message", "connection-test")
            }

            val request = Request.Builder()
                .url(backendUrl)
                .addHeader("Content-Type", "application/json")
                .addHeader("x-bridge-secret", bridgeSecret)
                .post(json.toString().toRequestBody(JSON_MEDIA))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val code = response.code
                return@withContext when (code) {
                    200, 400 -> Result(
                        true,
                        "CONNECTED",
                        "Backend reachable (HTTP $code)"
                    )
                    401 -> Result(
                        false,
                        "UNAUTHORIZED",
                        "Secret key is wrong"
                    )
                    else -> Result(
                        false,
                        "HTTP_$code",
                        "Server returned HTTP $code"
                    )
                }
            }
        } catch (e: Exception) {
            return@withContext Result(
                false,
                "NETWORK_ERROR",
                e.message ?: "Cannot reach server"
            )
        }
    }
}
