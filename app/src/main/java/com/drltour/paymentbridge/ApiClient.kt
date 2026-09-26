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

object ApiClient {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    // Fast client — shorter timeouts for quicker response
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    data class Result(
        val success: Boolean,
        val status: String,
        val message: String
    )

    suspend fun sendPayment(
        context: Context,
        payment: PaymentParser.PaymentData
    ): Result = withContext(Dispatchers.IO) {

        val backendUrl = Prefs.getBackendUrl(context)
        val bridgeSecret = Prefs.getBridgeSecret(context)

        if (backendUrl.isBlank()) {
            return@withContext Result(
                false,
                "MISSING_URL",
                "Backend URL not configured"
            )
        }

        if (bridgeSecret.isBlank()) {
            return@withContext Result(
                false,
                "MISSING_SECRET",
                "Bridge Secret not configured"
            )
        }

        try {
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
                        false,
                        "HTTP_${response.code}",
                        "Server returned ${response.code}"
                    )
                }

                try {
                    val jsonResp = JSONObject(responseBody)
                    val ok = jsonResp.optBoolean("ok", false)
                    val status = jsonResp.optString("status", "UNKNOWN")
                    val message = jsonResp.optString("message", "")

                    return@withContext Result(
                        ok,
                        status,
                        message.ifBlank { "Server responded: $status" }
                    )
                } catch (_: Exception) {
                    return@withContext Result(
                        true,
                        "OK",
                        "Sent (response not JSON)"
                    )
                }
            }
        } catch (e: Exception) {
            LogManager.add(context, "ERROR", "Network error: ${e.message}")
            return@withContext Result(
                false,
                "NETWORK_ERROR",
                e.message ?: "Network error"
            )
        }
    }

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
