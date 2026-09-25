package com.drltour.paymentbridge

/**
 * PaymentParser — Parses bKash / Nagad / Rocket payment notifications.
 *
 * Handles:
 * - Different spacing / casing
 * - Decimal amounts
 * - Missing sender / amount / TrxID (returns null → ignore notification)
 * - Malformed input (never crashes)
 */
object PaymentParser {

    data class PaymentData(
        val method: String,
        val amount: Double,
        val senderPhone: String,
        val trxId: String,
        val originalMessage: String
    )

    // ─────────────────────────────────────────────────────────────
    // Provider detection keywords
    // ─────────────────────────────────────────────────────────────
    private val BKASH_KEYWORDS = listOf("bkash", "bKash", "BKASH")
    private val NAGAD_KEYWORDS = listOf("nagad", "Nagad", "NAGAD")
    private val ROCKET_KEYWORDS = listOf("rocket", "Rocket", "ROCKET", "dbbl")

    // Phrases that indicate incoming money received (must match at least one)
    private val RECEIVE_PHRASES = listOf(
        "you have received",
        "you've received",
        "you have got",
        "money received",
        "received tk",
        "received taka",
        "cash in",
        "cash-in",
        "payment received",
        "পেয়েছেন",
        "পেয়েছি"
    )

    // ─────────────────────────────────────────────────────────────
    // Regex patterns (Kotlin inline regex)
    // ─────────────────────────────────────────────────────────────

    // Amount: "Tk 10.00" / "Tk. 10.00" / "Tk 10" / "BDT 10.00" / "10.00 Tk"
    // Also Bengali "৳ ১০.০০" is hard to normalize, so we skip Bengali numerals safely.
    private val AMOUNT_REGEX = Regex(
        """(?:Tk\.?|BDT\.?|৳)\s*([0-9]+(?:[.,][0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE
    )

    // Sender phone: Bangladeshi number 01XXXXXXXXX (11 digits)
    private val SENDER_PHONE_REGEX = Regex(
        """(?:from|sender|num(?:ber)?|phone)?\s*(01[3-9][0-9]{8})""",
        RegexOption.IGNORE_CASE
    )

    // TrxID: "TrxID XXXXXXXXX" or "Txn ID: XXXX" or "Transaction ID XXXXX"
    private val TRXID_REGEX = Regex(
        """(?:trx\s*id|trxid|txn\s*id|txnid|transaction\s*id|transactionid)\s*[:\-]?\s*([A-Za-z0-9]{6,20})""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Main parse function.
     * Returns PaymentData if the notification is a valid incoming payment.
     * Returns null otherwise — the caller should safely ignore the notification.
     */
    fun parse(title: String?, text: String?): PaymentData? {
        return try {
            val combined = ("${title ?: ""} ${text ?: ""}").trim()
            if (combined.isBlank()) return null

            val lower = combined.lowercase()

            // 1. Detect provider
            val method = detectProvider(combined) ?: return null

            // 2. Confirm it's an incoming payment notification (avoid outgoing / OTP / promo)
            val looksLikeReceive = RECEIVE_PHRASES.any { lower.contains(it.lowercase()) }
            if (!looksLikeReceive) return null

            // 3. Extract amount
            val amountMatch = AMOUNT_REGEX.find(combined) ?: return null
            val amountRaw = amountMatch.groupValues[1].replace(",", ".")
            val amount = amountRaw.toDoubleOrNull() ?: return null
            if (amount <= 0.0) return null

            // 4. Extract sender phone
            val senderMatch = SENDER_PHONE_REGEX.find(combined) ?: return null
            val senderPhone = senderMatch.groupValues[1]
            if (senderPhone.length != 11) return null

            // 5. Extract TrxID
            val trxMatch = TRXID_REGEX.find(combined) ?: return null
            val trxId = trxMatch.groupValues[1].uppercase()
            if (trxId.length < 6) return null

            PaymentData(
                method = method,
                amount = amount,
                senderPhone = senderPhone,
                trxId = trxId,
                originalMessage = combined
            )
        } catch (_: Exception) {
            // Never crash because of malformed notification
            null
        }
    }

    private fun detectProvider(text: String): String? {
        val lower = text.lowercase()
        // Order matters: bKash first (has distinct brand name)
        if (BKASH_KEYWORDS.any { lower.contains(it.lowercase()) }) return "bkash"
        if (NAGAD_KEYWORDS.any { lower.contains(it.lowercase()) }) return "nagad"
        if (ROCKET_KEYWORDS.any { lower.contains(it.lowercase()) }) return "rocket"
        return null
    }

    /**
     * Mask a phone number for UI display: 01575477403 → 0157*****03
     */
    fun maskPhone(phone: String): String {
        return try {
            if (phone.length != 11) phone
            else phone.substring(0, 4) + "*****" + phone.substring(9)
        } catch (_: Exception) {
            phone
        }
    }

    /**
     * Mask a TrxID for UI display: DIO3TJEN17 → DIO******17
     */
    fun maskTrxId(trxId: String): String {
        return try {
            if (trxId.length <= 6) trxId
            else trxId.substring(0, 3) + "******" + trxId.substring(trxId.length - 2)
        } catch (_: Exception) {
            trxId
        }
    }
}
