package dev.alenajam.opendialer.feature.inCall.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.TelecomManager
import android.widget.RemoteViews
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.alenajam.opendialer.core.common.CallGuardLabelClient
import dev.alenajam.opendialer.core.common.ui.contactAvatarColors
import dev.alenajam.opendialer.core.common.ui.contactAvatarColorKey
import dev.alenajam.opendialer.feature.inCall.R
import dev.alenajam.opendialer.feature.inCall.ui.InCallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallNotificationManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val callManager: CallManager
) {
    companion object {
        private const val CHANNEL_ID_INCOMING_CALLS = "com.callguard.dialer.notification_channel.incoming_calls"
        private const val CHANNEL_ID_ONGOING_CALLS = "com.callguard.dialer.notification_channel.ongoing_calls"
        private const val CHANNEL_ID_OUTGOING_CALLS = "com.callguard.dialer.notification_channel.outgoing_calls"
        internal const val CHANNEL_ID_MISSED_CALLS = "com.callguard.dialer.notification_channel.missed_calls"
        private const val NOTIFICATION_ID_CALL = 1
        internal const val MISSED_CALL_NOTIFICATION_ID = 1002
        private const val INTENT_ACTION_CALL_BUTTON_CLICK_ACCEPT = "com.callguard.dialer.CALL_ACCEPT"
        private const val INTENT_ACTION_CALL_BUTTON_CLICK_DECLINE = "com.callguard.dialer.CALL_DECLINE"
        private const val ACTION_SHOW_MISSED_CALLS_NOTIFICATION =
            "android.telecom.action.SHOW_MISSED_CALLS_NOTIFICATION"
    }

    private var callService: InCallServiceImpl? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observationJob: Job? = null
    private var eventObservationJob: Job? = null

    /**
     * 号码 -> 横幅第二行的标签（CallGuard 提供）。
     *
     * 非全屏模式下来电只有 heads-up 横幅，用户不点开就看不到任何信息。
     * 所以响铃时先画号码，查到标签后静默更新一次（`setOnlyAlertOnce` 保证不重弹）。
     * 空字符串表示"查过了，没标签"。
     */
    private val bannerLabels = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val bannerLookups = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 监听 CallGuard 标签变更，用于事后刷新横幅。 */
    private var labelObserver: ContentObserver? = null

    init {
        setupNotificationChannels()
    }

    fun attach(service: InCallServiceImpl) {
        this.callService = service
        startObserving()
        registerLabelObserver()
    }

    fun isAttached(): Boolean = callService != null

    fun detach() {
        observationJob?.cancel()
        observationJob = null
        eventObservationJob?.cancel()
        eventObservationJob = null
        unregisterLabelObserver()
        removeCallNotification()
        this.callService = null
    }

    private fun startObserving() {
        observationJob?.cancel()
        observationJob = scope.launch {
            callManager.displayState.collectLatest { state ->
                val primary = state.primary
                if (primary != null) {
                    handleCallNotification(primary, primary.state)
                } else {
                    removeCallNotification()
                }
            }
        }
        eventObservationJob = scope.launch {
            callManager.events.collectLatest { event ->
                if (event is CallEvent.MissedCall) notifyMissedCall(event)
            }
        }
    }

    private fun handleCallNotification(call: OngoingCall, state: Int) {
        when (state) {
            Call.STATE_RINGING -> notifyIncomingCall(call)
            Call.STATE_DIALING -> notifyOutgoingCall(call)
            Call.STATE_ACTIVE -> notifyOngoingCall(call)
            Call.STATE_HOLDING -> notifyOnHoldCall(call)
            Call.STATE_DISCONNECTING -> notifyDisconnectingCall(call)
            else -> removeCallNotification()
        }
    }

    private fun setupNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val channels = listOf(
                NotificationChannel(CHANNEL_ID_INCOMING_CALLS, context.getString(R.string.channel_incoming_calls), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_ID_ONGOING_CALLS, context.getString(R.string.channel_ongoing_calls), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_ID_OUTGOING_CALLS, context.getString(R.string.channel_outgoing_calls), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_ID_MISSED_CALLS, context.getString(R.string.channel_missed_calls), NotificationManager.IMPORTANCE_DEFAULT)
            )
            channels
                .filter { it.id != CHANNEL_ID_MISSED_CALLS }
                .forEach { it.setSound(null, null) }
            nm.createNotificationChannels(channels)
            // Remove the unused upstream channels left by older builds. They never post
            // notifications, but otherwise appear as duplicate entries in Settings.
            listOf(
                "dev.alenajam.opendialer.notification_channel.incoming_calls",
                "dev.alenajam.opendialer.notification_channel.ongoing_calls",
                "dev.alenajam.opendialer.notification_channel.outgoing_calls"
            ).forEach { nm.deleteNotificationChannel(it) }
        }
    }

    private fun notifyCall(channelId: String, call: OngoingCall, type: CallType) {
        // Only the default dialer owns call notifications. If another dialer has the role,
        // Telecom/the other dialer is responsible; posting here would duplicate the same call.
        if (!isDefaultDialer()) return

        // Self-managed VoIP calls are owned by their app (and by Telecom when another
        // unholdable call makes the system show its conflict notification). The dialer only
        // owns Telecom-managed calls, so never post a second notification for them.
        if (call.call.details.hasProperty(Call.Details.PROPERTY_SELF_MANAGED)) return

        val caller = callerLabel(call.callerName, call.callerNumber)
        val label = bannerLabels[call.callerNumber].orEmpty()
        val subtitle = label.ifEmpty {
            when (type) {
                CallType.INCOMING -> context.getString(R.string.call_ringing_title)
                CallType.OUTGOING -> context.getString(R.string.call_dialing_title)
                CallType.ONGOING -> ""
            }
        }
        val intent = Intent(context, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT or
                    Intent.FLAG_ACTIVITY_NEW_DOCUMENT
        }
        val pendingIntent = PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        builder.setSmallIcon(R.drawable.ic_notification_call)
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            // 关键：状态变化（RINGING→ACTIVE→HOLDING）和标签补全都只是「更新内容」，
            // 不应该每次都重新弹 heads-up 打扰用户。
            .setOnlyAlertOnce(true)

        // A CallStyle notification does not make the activity full-screen by itself.
        // This is needed on Android 12+ as well as on older releases so the ringing
        // call can be presented when the device is locked.
        //
        // 第二个参数是「锁屏时是否启动」：传 false 表示只点亮屏幕弹横幅，
        // 不绕过锁屏强行拉起界面。用户要的是「来电不打扰」而不是「来电抢占屏幕」。
        if (type == CallType.INCOMING && canUseFullScreenIntent()) {
            builder.setFullScreenIntent(pendingIntent, false)
        }

        // 自定义 heads-up 布局：两行文字 + 红绿圆按钮。
        // 不用 CallStyle 是因为它的四个槽位放不下标签（person.name 只剩 3 个汉字，
        // setSubText 被忽略），详见 CALLGUARD-PATCH.md。
        val views = buildCallViews(caller, subtitle, type)
        builder.setCustomContentView(views)
        builder.setCustomBigContentView(views)
        builder.setCustomHeadsUpContentView(views)

        // 通知栏折叠/展开状态下的按钮（自定义视图之外，保证下拉后也能直接操作）
        if (type == CallType.INCOMING) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_call_decline),
                    context.getString(R.string.action_decline),
                    getDeclineIntent()
                ).build()
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_call_answer),
                    context.getString(R.string.action_answer),
                    getAcceptIntent()
                ).build()
            )
        }

        callService?.let { service ->
            val notification = builder.build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                service.startForeground(NOTIFICATION_ID_CALL, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            } else {
                service.startForeground(NOTIFICATION_ID_CALL, notification)
            }
        }

        // 响铃时通知已经画出号码，联网查到的标签要补上（静默更新，不重弹）
        ensureBannerLabel(call.callerNumber)
    }

    /**
     * 给横幅补上 CallGuard 的标签。
     *
     * 时序：响铃瞬间通知已发出，而在线查询要 0.7~6 秒。查到后重新 notify 一次即可，
     * `setOnlyAlertOnce(true)` 保证这不会重新弹出 heads-up。
     */
    private fun ensureBannerLabel(number: String) {
        val digits = number.trim()
        if (digits.isEmpty()) return
        if (bannerLabels.containsKey(digits)) return
        if (!bannerLookups.add(digits)) return

        scope.launch {
            val summary = try {
                CallGuardLabelClient.lookup(context, digits)?.bannerSummary().orEmpty()
            } catch (t: Throwable) {
                ""
            } finally {
                bannerLookups.remove(digits)
            }
            if (bannerLabels.size > 32) bannerLabels.clear()
            bannerLabels[digits] = summary
            if (summary.isEmpty()) return@launch

            callManager.displayState.value.primary?.let { primary ->
                handleCallNotification(primary, primary.state)
            }
        }
    }

    /**
     * 构建横幅的自定义布局。
     *
     * 文字颜色必须按系统深浅色主题动态设置——RemoteViews 不解析主题属性，
     * 写死黑色的话在深色模式下会看不见。
     */
    private fun buildCallViews(caller: String, subtitle: String, type: CallType): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.notification_call_custom)
        views.setTextViewText(R.id.call_custom_title, caller)
        views.setTextViewText(R.id.call_custom_subtitle, subtitle)

        val night = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val textColor = if (night) 0xFFFFFFFF.toInt() else 0xFF1F1F1F.toInt()
        views.setTextColor(R.id.call_custom_title, textColor)
        views.setTextColor(R.id.call_custom_subtitle, textColor)

        views.setOnClickPendingIntent(R.id.call_custom_decline, getDeclineIntent())
        views.setOnClickPendingIntent(
            R.id.call_custom_answer,
            if (type == CallType.INCOMING) getAcceptIntent() else getDeclineIntent()
        )
        return views
    }

    /**
     * 监听 CallGuard 的标签变更，让横幅能"事后"补上信息。
     *
     * 时序：响铃瞬间只有号码/归属地，在线查询 0.7~6 秒后才回来。
     * CallGuard 写完缓存会 notifyChange，这里收到就作废本地缓存、重查、静默刷新横幅。
     */
    private fun registerLabelObserver() {
        if (labelObserver != null) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val primary = callManager.displayState.value.primary ?: return
                val digits = primary.callerNumber.trim()
                if (digits.isEmpty()) return
                bannerLabels.remove(digits)
                bannerLookups.remove(digits)
                CallGuardLabelClient.invalidate(digits)
                ensureBannerLabel(digits)
            }
        }
        try {
            context.contentResolver.registerContentObserver(
                CallGuardLabelClient.labelUri(), true, observer
            )
            labelObserver = observer
        } catch (t: Throwable) {
            // CallGuard 没装 / provider 被停用：静默跳过
        }
    }

    private fun unregisterLabelObserver() {
        labelObserver?.let {
            try {
                context.contentResolver.unregisterContentObserver(it)
            } catch (ignored: Throwable) {
            }
        }
        labelObserver = null
    }

    private fun getAcceptIntent(): PendingIntent {
        val intent = Intent(context, CallButtonsListener::class.java).apply { action = INTENT_ACTION_CALL_BUTTON_CLICK_ACCEPT }
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun getDeclineIntent(): PendingIntent {
        val intent = Intent(context, CallButtonsListener::class.java).apply { action = INTENT_ACTION_CALL_BUTTON_CLICK_DECLINE }
        return PendingIntent.getBroadcast(context, 1, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notifyIncomingCall(call: OngoingCall) = notifyCall(CHANNEL_ID_INCOMING_CALLS, call, CallType.INCOMING)
    private fun notifyOutgoingCall(call: OngoingCall) = notifyCall(CHANNEL_ID_OUTGOING_CALLS, call, CallType.OUTGOING)
    private fun notifyOngoingCall(call: OngoingCall) = notifyCall(CHANNEL_ID_ONGOING_CALLS, call, CallType.ONGOING)
    private fun notifyOnHoldCall(call: OngoingCall) = notifyCall(CHANNEL_ID_ONGOING_CALLS, call, CallType.ONGOING)
    private fun notifyDisconnectingCall(call: OngoingCall) = notifyCall(CHANNEL_ID_ONGOING_CALLS, call, CallType.ONGOING)

    private fun canUseFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return context.getSystemService(NotificationManager::class.java)
            ?.canUseFullScreenIntent() == true
    }

    private fun isDefaultDialer(): Boolean {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
        return telecom.defaultDialerPackage == context.packageName
    }

    /** True when Telecom will delegate missed calls to this dialer instead of posting its own. */
    private fun canOwnMissedCallNotification(): Boolean {
        if (!isDefaultDialer()) return false
        val intent = Intent(ACTION_SHOW_MISSED_CALLS_NOTIFICATION)
            .setPackage(context.packageName)
        return context.packageManager
            .queryBroadcastReceivers(intent, 0)
            .any { it.activityInfo?.packageName == context.packageName }
    }

    private fun notifyMissedCall(call: CallEvent.MissedCall) {
        // Self-managed missed calls belong to the VoIP app, matching AOSP Dialer.
        if (call.selfManaged) return

        // If Telecom is not delegating missed calls to us, its own notification owns this
        // call. Do not post a second one from the InCallService event path.
        if (!canOwnMissedCallNotification()) return

        // Rebuild from the call log on an IO thread. The event path and the Telecom receiver
        // both converge on the same stable notification tags, so one call cannot become two
        // notifications even if the service unbinds between the two paths.
        scope.launch(Dispatchers.IO) {
            MissedCallNotifications.refresh(
                context,
                fallbackName = call.callerName,
                fallbackNumber = call.callerNumber
            )
        }
    }

    fun cancelMissedCallNotification() {
        MissedCallNotifications.clear(context)
    }

    fun notifyMissedCallFromTelecom(number: String?, count: Int) {
        if (!canOwnMissedCallNotification()) return
        if (count == 0) {
            cancelMissedCallNotification()
            return
        }
        MissedCallNotifications.refresh(
            context,
            fallbackNumber = number,
            fallbackCount = count
        )
    }
    private fun createCallerIcon(call: OngoingCall): Icon {
        call.callerImageUri?.takeIf { it.isNotBlank() }?.let { uri ->
            return Icon.createWithContentUri(Uri.parse(uri))
        }

        val name = call.callerName?.takeIf { it.isNotBlank() && it != call.callerNumber }
        val colors = contactAvatarColors(contactAvatarColorKey(name, call.callerNumber))
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = colors.background.toInt()
        canvas.drawCircle(64f, 64f, 64f, paint)
        paint.color = colors.foreground.toInt()

        if (name.isNullOrBlank()) {
            canvas.drawCircle(64f, 43f, 17f, paint)
            canvas.drawRoundRect(RectF(34f, 66f, 94f, 112f), 30f, 30f, paint)
        } else {
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            paint.textSize = 72f
            val baseline = 64f - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(name.take(1).uppercase(), 64f, baseline, paint)
        }

        return Icon.createWithBitmap(bitmap)
    }

    private fun callerLabel(callerName: String?, callerNumber: String): String =
        callerName?.trim()?.takeIf(String::isNotEmpty)
            ?: callerNumber.trim().takeIf(String::isNotEmpty)
            ?: context.getString(R.string.anonymous)

    private fun removeCallNotification() {
        @Suppress("DEPRECATION")
        callService?.stopForeground(true)
    }

    private enum class CallType { INCOMING, ONGOING, OUTGOING }

    @AndroidEntryPoint
    class CallButtonsListener : BroadcastReceiver() {
        @Inject
        lateinit var callManager: CallManager

        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val mainCall = callManager.displayState.value.primary ?: return

            if (action == INTENT_ACTION_CALL_BUTTON_CLICK_ACCEPT) {
                callManager.answer(mainCall)
                InCallActivity.start(context)
            } else {
                callManager.hangup(mainCall)
            }
        }
    }
}
