package dev.alenajam.opendialer.feature.inCall.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.CallLog.Calls
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import dev.alenajam.opendialer.feature.inCall.R

/**
 * AOSP-Dialer-style missed-call notifications.
 *
 * The call log is the source of truth. Both the InCallService event path and the Telecom
 * receiver call [refresh], so the same missed call always maps to the same stable tag and
 * overwrites one notification instead of creating a second one.
 */
object MissedCallNotifications {

    private const val GROUP_KEY = "com.callguard.dialer.MISSED_CALLS"
    private const val SUMMARY_TAG = "missed-calls-summary"
    private const val CHILD_TAG_PREFIX = "missed-call-"

    const val ACTION_DISMISS = "com.callguard.dialer.MISSED_CALL_DISMISS"
    const val EXTRA_CALL_ID = "com.callguard.dialer.extra.CALL_ID"

    private const val NOTIFICATION_ID = CallNotificationManager.MISSED_CALL_NOTIFICATION_ID
    private const val CHANNEL_ID = CallNotificationManager.CHANNEL_ID_MISSED_CALLS

    private data class MissedCall(
        val id: Long,
        val number: String,
        val name: String?,
        val date: Long,
        val accountComponent: String?,
        val accountId: String?
    )

    /**
     * Rebuild the missed-call stack from the call log.
     *
     * @param fallbackName/fallbackNumber used only when the call log cannot be queried.
     * @param fallbackCount when non-null, the notification may fall back to the Telecom
     *   number/count if the call log is temporarily unavailable.
     * @param clearIfEmpty when true and the query succeeds with no calls, clear all missed
     *   notifications and mark them read. Receiver count=0 uses [clear] directly instead.
     */
    @Synchronized
    fun refresh(
        context: Context,
        fallbackName: String? = null,
        fallbackNumber: String? = null,
        fallbackCount: Int? = null,
        clearIfEmpty: Boolean = false
    ) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val calls = queryNewMissedCalls(context)

        // Remove notifications from older app versions (random ids / untagged 1002).
        cancelLegacyNotifications(nm)

        if (calls == null) {
            // No call-log permission or a transient provider failure. Fall back to the
            // number/count supplied by Telecom rather than dropping the notification.
            if (fallbackCount != null && fallbackCount > 0) {
                postFallbackSummary(context, nm, fallbackName, fallbackNumber, fallbackCount)
            }
            return
        }

        if (calls.isEmpty()) {
            // The event path can run before the call-log row is inserted. The Telecom
            // receiver will refresh again; do not clear an existing stack from this path.
            if (fallbackCount != null && fallbackCount > 0) {
                postFallbackSummary(context, nm, fallbackName, fallbackNumber, fallbackCount)
            } else if (clearIfEmpty) {
                clear(context)
            }
            return
        }

        val visible = calls.filterNot {
            isSelfManaged(context, it.accountComponent, it.accountId)
        }
        if (visible.isEmpty()) {
            // Self-managed missed calls belong to their VoIP app, not the dialer.
            return
        }

        postSummary(context, nm, visible.size)
        val keepTags = HashSet<String>()
        visible.forEach { call ->
            val tag = childTag(call.id)
            keepTags.add(tag)
            postChild(context, nm, call, tag)
        }

        // Calls that were read/deleted elsewhere should no longer leave orphan children.
        nm.activeNotifications
            .filter {
                it.notification.category == Notification.CATEGORY_MISSED_CALL &&
                    it.tag?.startsWith(CHILD_TAG_PREFIX) == true &&
                    it.tag !in keepTags
            }
            .forEach { cancel(nm, it.tag, it.id) }
    }

    /** Clear all missed notifications and mark every new missed call as read. */
    @Synchronized
    fun clear(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        markAllMissedCallsRead(context)
        cancelAllNotifications(nm)
    }

    /** Dismiss one missed call (id < 0 means all). */
    @Synchronized
    fun dismiss(context: Context, callId: Long) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (callId < 0) {
            clear(context)
            return
        }
        markMissedCallRead(context, callId)
        val tag = childTag(callId)
        // Decide before cancelling: cancellation is asynchronous, so checking after
        // nm.cancel() can still see the child we just removed and leave an empty summary.
        val hasOtherChildren = nm.activeNotifications.any {
            it.notification.category == Notification.CATEGORY_MISSED_CALL &&
                it.tag?.startsWith(CHILD_TAG_PREFIX) == true &&
                it.tag != tag
        }
        cancel(nm, tag, NOTIFICATION_ID)
        if (!hasOtherChildren) {
            cancel(nm, SUMMARY_TAG, NOTIFICATION_ID)
        }
    }

    private fun queryNewMissedCalls(context: Context): List<MissedCall>? {
        val projection = arrayOf(
            Calls._ID,
            Calls.NUMBER,
            Calls.CACHED_NAME,
            Calls.DATE,
            Calls.PHONE_ACCOUNT_COMPONENT_NAME,
            Calls.PHONE_ACCOUNT_ID
        )
        val selection = "${Calls.NEW} = 1 AND ${Calls.TYPE} = ? AND ${Calls.IS_READ} IS NOT 1"
        return try {
            context.contentResolver.query(
                Calls.CONTENT_URI,
                projection,
                selection,
                arrayOf(Calls.MISSED_TYPE.toString()),
                Calls.DEFAULT_SORT_ORDER
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(Calls._ID)
                val numberIndex = cursor.getColumnIndexOrThrow(Calls.NUMBER)
                val nameIndex = cursor.getColumnIndexOrThrow(Calls.CACHED_NAME)
                val dateIndex = cursor.getColumnIndexOrThrow(Calls.DATE)
                val accountComponentIndex =
                    cursor.getColumnIndexOrThrow(Calls.PHONE_ACCOUNT_COMPONENT_NAME)
                val accountIdIndex = cursor.getColumnIndexOrThrow(Calls.PHONE_ACCOUNT_ID)
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            MissedCall(
                                id = cursor.getLong(idIndex),
                                number = cursor.getString(numberIndex).orEmpty(),
                                name = cursor.getString(nameIndex),
                                date = cursor.getLong(dateIndex),
                                accountComponent = cursor.getString(accountComponentIndex),
                                accountId = cursor.getString(accountIdIndex)
                            )
                        )
                    }
                }
            }
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun isSelfManaged(
        context: Context,
        componentName: String?,
        accountId: String?
    ): Boolean {
        if (componentName.isNullOrEmpty() || accountId.isNullOrEmpty()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            val component = ComponentName.unflattenFromString(componentName) ?: return false
            val handle = PhoneAccountHandle(component, accountId)
            val account = context.getSystemService(TelecomManager::class.java)
                ?.getPhoneAccount(handle) ?: return false
            account.hasCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
        } catch (_: SecurityException) {
            false
        }
    }

    private fun postSummary(context: Context, nm: NotificationManager, count: Int) {
        val openDialer = openDialerPendingIntent(context, 1000)
        val builder = notificationBuilder(context, CallNotificationManager.CHANNEL_ID_MISSED_CALLS)
            .setSmallIcon(R.drawable.ic_notification_call)
            .setContentTitle(context.getString(R.string.notification_missed_calls_title))
            .setContentText(context.getString(R.string.notification_missed_calls_count, count))
            .setContentIntent(openDialer)
            .setDeleteIntent(deleteIntent(context, -1))
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
        nm.notify(SUMMARY_TAG, NOTIFICATION_ID, builder.build())
    }

    private fun postChild(
        context: Context,
        nm: NotificationManager,
        call: MissedCall,
        tag: String
    ) {
        val caller = callerLabel(context, call.name, call.number)
        val builder = notificationBuilder(context, CallNotificationManager.CHANNEL_ID_MISSED_CALLS)
            .setSmallIcon(R.drawable.ic_notification_call)
            .setContentTitle(context.getString(R.string.notification_missed_call_title, caller))
            .setContentIntent(openDialerPendingIntent(context, call.id.toInt()))
            .setDeleteIntent(deleteIntent(context, call.id))
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setGroup(GROUP_KEY)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(call.date)
        if (call.name?.isNotBlank() == true && call.number.isNotBlank()) {
            builder.setContentText(call.number)
        }
        if (call.number.isNotBlank()) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_notification_call),
                    context.getString(R.string.notification_call_back),
                    callPendingIntent(context, call.id.toInt(), call.number, Intent.ACTION_CALL)
                ).build()
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_notification_call),
                    context.getString(R.string.notification_message),
                    callPendingIntent(context, (call.id + 1).toInt(), call.number, Intent.ACTION_SENDTO)
                ).build()
            )
        }
        nm.notify(tag, NOTIFICATION_ID, builder.build())
    }

    private fun postFallbackSummary(
        context: Context,
        nm: NotificationManager,
        fallbackName: String?,
        fallbackNumber: String?,
        count: Int
    ) {
        val caller = callerLabel(context, fallbackName, fallbackNumber.orEmpty())
        val builder = notificationBuilder(context, CallNotificationManager.CHANNEL_ID_MISSED_CALLS)
            .setSmallIcon(R.drawable.ic_notification_call)
            .setContentTitle(
                if (count == 1) {
                    context.getString(R.string.notification_missed_call_title, caller)
                } else {
                    context.getString(R.string.notification_missed_calls_title)
                }
            )
            .setContentText(context.getString(R.string.notification_missed_calls_count, count))
            .setContentIntent(openDialerPendingIntent(context, 1000))
            .setDeleteIntent(deleteIntent(context, -1))
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
        nm.notify(SUMMARY_TAG, NOTIFICATION_ID, builder.build())
    }

    private fun notificationBuilder(context: Context, channelId: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

    private fun openDialerPendingIntent(context: Context, requestCode: Int): PendingIntent {
        val launch = (
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(context.packageName)
            ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun callPendingIntent(
        context: Context,
        requestCode: Int,
        number: String,
        action: String
    ): PendingIntent {
        val uri = if (action == Intent.ACTION_CALL) {
            Uri.fromParts("tel", number, null)
        } else {
            Uri.fromParts("smsto", number, null)
        }
        val intent = Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun deleteIntent(context: Context, callId: Long): PendingIntent {
        val intent = Intent(context, MissedCallDismissReceiver::class.java).apply {
            action = ACTION_DISMISS
            putExtra(EXTRA_CALL_ID, callId)
        }
        return PendingIntent.getBroadcast(
            context,
            if (callId < 0) 1001 else callId.toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun callerLabel(context: Context, name: String?, number: String): String =
        name?.trim()?.takeIf(String::isNotEmpty)
            ?: number.trim().takeIf(String::isNotEmpty)
            ?: context.getString(R.string.anonymous)

    private fun childTag(callId: Long): String = "$CHILD_TAG_PREFIX$callId"

    private fun cancel(nm: NotificationManager, tag: String?, id: Int) {
        if (tag == null) nm.cancel(id) else nm.cancel(tag, id)
    }

    private fun cancelAllNotifications(nm: NotificationManager) {
        nm.cancel(SUMMARY_TAG, NOTIFICATION_ID)
        nm.activeNotifications
            .filter {
                it.notification.category == Notification.CATEGORY_MISSED_CALL ||
                    it.tag?.startsWith(CHILD_TAG_PREFIX) == true
            }
            .forEach { cancel(nm, it.tag, it.id) }
    }

    private fun cancelLegacyNotifications(nm: NotificationManager) {
        nm.activeNotifications
            .filter {
                it.notification.category == Notification.CATEGORY_MISSED_CALL &&
                    it.tag != SUMMARY_TAG &&
                    it.tag?.startsWith(CHILD_TAG_PREFIX) != true
            }
            .forEach { cancel(nm, it.tag, it.id) }
    }

    private fun markMissedCallRead(context: Context, callId: Long) {
        updateRead(context, "${Calls._ID} = ?", arrayOf(callId.toString()))
    }

    private fun markAllMissedCallsRead(context: Context) {
        updateRead(
            context,
            "${Calls.NEW} = 1 AND ${Calls.TYPE} = ?",
            arrayOf(Calls.MISSED_TYPE.toString())
        )
    }

    private fun updateRead(context: Context, where: String, args: Array<String>) {
        try {
            val values = ContentValues().apply {
                put(Calls.NEW, 0)
                put(Calls.IS_READ, 1)
            }
            context.contentResolver.update(Calls.CONTENT_URI, values, where, args)
        } catch (_: SecurityException) {
            // Call-log write permission can be revoked outside the dialer role.
        } catch (_: RuntimeException) {
        }
    }
}
