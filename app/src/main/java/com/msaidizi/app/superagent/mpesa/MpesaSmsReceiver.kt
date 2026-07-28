package com.msaidizi.app.superagent.mpesa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * MpesaSmsReceiver — BroadcastReceiver that listens for incoming M-Pesa SMS.
 *
 * When an SMS arrives from M-Pesa (sender: "MPESA" or "Safaricom"):
 *   1. Extract SMS body
 *   2. Parse with MpesaSmsParser
 *   3. Auto-reconcile with MpesaSmsReconciler
 *   4. Show notification if auto-recorded
 *
 * Registration in AndroidManifest.xml:
 *   <receiver android:name=".superagent.mpesa.MpesaSmsReceiver"
 *             android:exported="true"
 *             android:permission="android.permission.BROADCAST_SMS">
 *     <intent-filter android:priority="999">
 *       <action android:name="android.provider.Telephony.SMS_RECEIVED" />
 *     </intent-filter>
 *   </receiver>
 *
 * Permission required:
 *   <uses-permission android:name="android.permission.RECEIVE_SMS" />
 *
 * Design:
 * - Uses application-scoped coroutine (not tied to receiver lifecycle)
 * - Only processes M-Pesa SMS (ignores all others)
 * - Deduplicates by receipt number (won't double-record)
 * - Shows subtle notification for auto-recorded transactions
 */
@AndroidEntryPoint
class MpesaSmsReceiver : BroadcastReceiver() {

    @Inject
    lateinit var reconciler: MpesaSmsReconciler

    @Inject
    lateinit var notificationManager: com.msaidizi.app.superagent.proactive.ProactiveNotificationManager

    companion object {
        private const val MPESA_SENDER = "MPESA"
        private const val SAFARICOM_SENDER = "SAFARICOM"

        /**
         * Whether auto-reconciliation is enabled.
         * Users can toggle this in settings.
         */
        var isEnabled = true
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!isEnabled) return

        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

        for (sms in messages) {
            val sender = sms.displayOriginatingAddress ?: continue
            val body = sms.messageBody ?: continue

            // Only process M-Pesa SMS
            if (!isMpesaSender(sender)) continue

            // Parse the SMS
            val transaction = MpesaSmsParser.parse(body) ?: continue

            Timber.i(
                "MpesaSmsReceiver: Detected M-Pesa SMS — %s KES %.0f from %s",
                transaction.type, transaction.amount, transaction.counterparty
            )

            // Use application-scoped coroutine for async work
            val pendingResult = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val result = reconciler.reconcile(transaction)

                    if (result.success) {
                        // Show notification for auto-recorded transaction
                        val emoji = when (transaction.category) {
                            TransactionCategory.SALE -> "💰"
                            TransactionCategory.EXPENSE -> "📤"
                            TransactionCategory.PURCHASE -> "📦"
                            else -> "📱"
                        }

                        notificationManager.postAlert(
                            type = "mpesa_auto",
                            title = "$emoji M-Pesa Auto-Recorded",
                            body = result.message,
                            priority = android.app.Notification.PRIORITY_LOW
                        )
                    }

                    Timber.i("MpesaSmsReceiver: Reconciliation complete — ${result.message}")
                } catch (e: Exception) {
                    Timber.e(e, "MpesaSmsReceiver: Reconciliation failed")
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private fun isMpesaSender(sender: String): Boolean {
        val upper = sender.uppercase()
        return upper.contains(MPESA_SENDER) || upper.contains(SAFARICOM_SENDER)
    }
}
