package com.msaidizi.app.core.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import timber.log.Timber

/**
 * SmsReceiver — Receives M-Pesa SMS messages for automatic transaction parsing.
 * 
 * When an M-Pesa SMS arrives, it triggers the SuperAgent to parse and record
 * the transaction automatically.
 * 
 * Design: arch_android.md — M-Pesa integration
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        for (message in messages) {
            val sender = message.displayOriginatingAddress ?: ""
            val body = message.messageBody ?: ""

            // Only process M-Pesa messages
            if (isMpesaMessage(sender, body)) {
                Timber.i("M-Pesa SMS received from: $sender")
                // TODO: Trigger SuperAgent to parse M-Pesa SMS
                // The actual parsing will be done by MpesaTool when the user
                // interacts with the agent, or via a background worker.
            }
        }
    }

    private fun isMpesaMessage(sender: String, body: String): Boolean {
        // M-Pesa messages come from short codes like "MPESA" or numbers
        val mpesaSenders = listOf("MPESA", "M-PESA", "Safaricom")
        val isMpesaSender = mpesaSenders.any { sender.contains(it, ignoreCase = true) }
        val isMpesaContent = body.contains("Confirmed", ignoreCase = true) &&
                            body.contains("Ksh", ignoreCase = true)
        return isMpesaSender || isMpesaContent
    }
}
