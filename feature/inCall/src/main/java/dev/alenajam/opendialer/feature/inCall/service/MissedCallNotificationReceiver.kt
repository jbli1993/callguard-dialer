package dev.alenajam.opendialer.feature.inCall.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Telecom only hands missed-call notifications to the default dialer when that dialer
 * declares a receiver for [ACTION_SHOW_MISSED_CALLS_NOTIFICATION]. Without this receiver
 * Telecom posts its own notification, whose content intent is an implicit
 * `ACTION_VIEW` for the call-log MIME type and therefore does not resolve to this dialer.
 *
 * Both this receiver and the [CallEvent.MissedCall] event path rebuild the notification
 * stack from the call log. The stable CallLog tags make the operation idempotent, so the
 * receiver can safely run even when the InCallService is still attached.
 */
@AndroidEntryPoint
class MissedCallNotificationReceiver : BroadcastReceiver() {
    @Inject
    lateinit var callNotificationManager: CallNotificationManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SHOW_MISSED_CALLS_NOTIFICATION) return

        val count = intent.getIntExtra(EXTRA_NOTIFICATION_COUNT, -1)
        if (count == 0) {
            callNotificationManager.cancelMissedCallNotification()
            return
        }

        // Always rebuild from the call log, even when the InCallService is attached. The event
        // path can run before the call-log row is committed; this broadcast runs after logging
        // and fills in any missing child. Both paths use stable CallLog tags, so rebuilding is
        // idempotent and cannot create a second notification for the same call.
        val number = intent.getStringExtra(EXTRA_NOTIFICATION_PHONE_NUMBER)
        if (count < 0 && number.isNullOrBlank()) return

        // The call-log query and notification rebuild must not run on the main thread. Keep
        // the receiver alive with goAsync so the process is not killed halfway through.
        val pending = goAsync()
        Thread {
            try {
                callNotificationManager.notifyMissedCallFromTelecom(number, count)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        // Keep these as literals: the corresponding TelecomManager constants are not all
        // part of the public SDK.
        private const val ACTION_SHOW_MISSED_CALLS_NOTIFICATION =
            "android.telecom.action.SHOW_MISSED_CALLS_NOTIFICATION"
        private const val EXTRA_NOTIFICATION_COUNT = "android.telecom.extra.NOTIFICATION_COUNT"
        private const val EXTRA_NOTIFICATION_PHONE_NUMBER =
            "android.telecom.extra.NOTIFICATION_PHONE_NUMBER"
    }
}
