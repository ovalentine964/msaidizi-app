package com.msaidizi.app.mpesa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.msaidizi.app.agent.BusinessAgent
import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
import com.msaidizi.app.gamification.GamificationEngine
import com.msaidizi.app.loops.MorningBriefingLoop
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * BroadcastReceiver that auto-captures M-Pesa transactions from SMS.
 *
 * When a worker receives an M-Pesa SMS (payment received, money sent, etc.),
 * this receiver automatically:
 * 1. Parses the SMS using MpesaSmsParser
 * 2. Creates a Transaction record
 * 3. Saves to local Room database
 * 4. Triggers gamification update
 * 5. Notifies MorningBriefingLoop of new transaction
 *
 * This is the #1 feature that makes Msaidizi feel like magic:
 * Valentine's mum gets M-Pesa SMS for every transaction. The app
 * auto-captures these and builds her financial picture without her
 * doing anything.
 *
 * ## SMS Formats Handled
 *
 * **Customer Payment Received:**
 * "QHK71G4YS0 Confirmed. Ksh100.00 received from JOHN DOE 254712345678 on 30/6/26 at 12:00 PM. New M-PESA balance is Ksh1,500.00."
 *
 * **Money Sent:**
 * "QHK71G4YS0 Confirmed. Ksh200.00 sent to JANE DOE 254798765432 on 30/6/26 at 1:00 PM for account ACC001. New M-PESA balance is Ksh1,300.00."
 *
 * **Pay Bill:**
 * "QHK71G4YS0 Confirmed. Ksh50.00 paid to KPLC. Account: ACC001. New M-PESA balance is Ksh950.00."
 *
 * **Withdrawal:**
 * "QHK71G4YS0 Confirmed. Ksh500.00 withdrawn from M-PESA. New M-PESA balance is Ksh450.00."
 *
 * ## Permissions Required
 *
 * ```xml
 * <uses-permission android:name="android.permission.RECEIVE_SMS" />
 * <uses-permission android:name="android.permission.READ_SMS" />
 * ```
 *
 * ## Registration (AndroidManifest.xml)
 *
 * ```xml
 * <receiver
 *     android:name=".mpesa.MpesaSmsReceiver"
 *     android:exported="true"
 *     android:permission="android.permission.BROADCAST_SMS">
 *     <intent-filter android:priority="999">
 *         <action android:name="android.provider.Telephony.SMS_RECEIVED" />
 *     </intent-filter>
 * </receiver>
 * ```
 */
@AndroidEntryPoint
class MpesaSmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MpesaSmsReceiver"
    }

    @Inject
    lateinit var businessAgent: BusinessAgent

    @Inject
    lateinit var gamificationEngine: GamificationEngine

    @Inject
    lateinit var morningBriefingLoop: MorningBriefingLoop

    private val smsParser = MpesaSmsParser()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Combine multi-part SMS into a single string
        val sender = messages[0].originatingAddress ?: return
        val body = messages.joinToString("") { it.messageBody ?: "" }

        // Quick check: is this an M-Pesa SMS?
        if (!smsParser.isMpesaSms(body)) return

        Timber.tag(TAG).d("M-Pesa SMS detected from %s", sender.take(6) + "****")

        // Parse the SMS
        val parsed = smsParser.parse(body)
        if (parsed == null) {
            Timber.tag(TAG).w("Failed to parse M-Pesa SMS: %s", body.take(80))
            return
        }

        Timber.tag(TAG).i(
            "M-Pesa transaction captured: %s KSh %.0f (%s)",
            parsed.receipt, parsed.amount, parsed.transactionType.name
        )

        // Process asynchronously — BroadcastReceiver has ~10s limit
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                processTransaction(parsed, context)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error processing M-Pesa SMS transaction")
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Process a parsed M-Pesa SMS transaction.
     * Saves to database, updates gamification, notifies briefing loop.
     */
    private suspend fun processTransaction(
        parsed: MpesaSmsTransaction,
        context: Context
    ) {
        // Build Transaction object
        val transaction = Transaction(
            id = 0, // Auto-generate
            type = parsed.transactionType,
            item = parsed.counterparty,
            totalAmount = parsed.amount,
            quantity = 1.0,
            paymentMethod = "mpesa",
            notes = "M-Pesa: ${parsed.receipt} — ${parsed.counterparty} | credit=${parsed.isCredit} | balance=${parsed.balance ?: 0.0}",
            createdAt = parsed.timestamp ?: (System.currentTimeMillis() / 1000)
        )

        // Save to database via BusinessAgent
        try {
            businessAgent.recordTransaction(transaction)
            Timber.tag(TAG).d("Transaction saved: %s", parsed.receipt)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to save M-Pesa transaction to database")
            return
        }

        // Update gamification (counts as recording an activity)
        try {
            if (parsed.transactionType == TransactionType.SALE) {
                gamificationEngine.onSaleRecorded()
            }
            gamificationEngine.onDailyActivity()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Gamification update failed for M-Pesa transaction")
        }

        // Notify MorningBriefingLoop of new transaction (for feedback loop)
        try {
            morningBriefingLoop.onTransactionAfterBriefing(transaction)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Briefing loop notification failed")
        }

        Timber.tag(TAG).i(
            "M-Pesa transaction fully processed: %s %s KSh %.0f",
            if (parsed.isCredit) "RECEIVED" else "SENT",
            parsed.receipt,
            parsed.amount
        )
    }
}
