package dev.alenajam.opendialer.feature.inCall.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives delete intents from the missed-call notification stack. */
class MissedCallDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MissedCallNotifications.ACTION_DISMISS) return
        MissedCallNotifications.dismiss(
            context,
            intent.getLongExtra(MissedCallNotifications.EXTRA_CALL_ID, -1)
        )
    }
}
