package dev.alenajam.opendialer.feature.inCall.ui

import android.app.Activity
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.alenajam.opendialer.core.common.getActivity
import dev.alenajam.opendialer.core.common.ui.AppProviders
import dev.alenajam.opendialer.core.common.CallGuardLabelClient
import dev.alenajam.opendialer.core.common.ExternalLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InCallScreen(
    viewModel: InCallViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val durationMillis by viewModel.activeCallDuration.collectAsStateWithLifecycle(0L)
    val context = LocalContext.current

    val canMerge = uiState.canMerge
    val telecomCanHold = uiState.canHold
    val hasSecondaryCall = uiState.hasSecondaryCall
    val secondaryCallerName = uiState.secondaryCallerName
    val canSwap = hasSecondaryCall
    // Add call uses the telecom-provided capability. It is hidden once a
    // secondary call is present (two independent calls or a conference plus a
    // held call), since the map size check is unreliable for conferences where
    // the parent call inflates the count.
    val canAddCall = uiState.canAddCall && !hasSecondaryCall
    // When two calls are present, Swap replaces Hold in the More panel (they
    // would otherwise both toggle the same primary/secondary state).
    val canHold = telecomCanHold && !canSwap
    // In a conference with a secondary call, splitting a participant would
    // produce three independent calls, so the split affordance is hidden.
    val canManageConference = uiState.canManageConference
    val showSplitInManage = canManageConference && !hasSecondaryCall

    var showManageSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // CallGuard 的来电身份信息（号段归属地 / 在线标记）。可选：没装或没数据时为 null，
    // 界面按系统默认显示，不受影响。查询走跨进程 ContentResolver.call，
    // 失败一律静默忽略——通话界面不能因为查标签而出问题。
    var externalLabel by remember(uiState.callId, uiState.callerNumber) {
        mutableStateOf<ExternalLabel?>(null)
    }

    // 时序问题：拨号器 ~300ms 就起来了，而 CallGuard 的联网查询要 1~6 秒。
    // 所以第一次查必然是空的（同一通电话、第一次来电的号码）。
    // CallGuard 查完写缓存时会 notifyChange，这里据此重查。
    var labelRevision by remember { mutableStateOf(0) }

    // 点过「用付费接口重查」、还在等 CallGuard 返回。按钮据此变「查询中…」并禁用。
    var lookupBusy by remember(uiState.callId, uiState.callerNumber) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // CallGuard 查完会 notifyChange → labelRevision 变化 → 收起"查询中"
    LaunchedEffect(labelRevision) { lookupBusy = false }

    // 兜底：15 秒没回来就恢复按钮，不能一直卡在"查询中…"
    LaunchedEffect(lookupBusy) {
        if (lookupBusy) {
            delay(15_000)
            lookupBusy = false
        }
    }

    LaunchedEffect(uiState.callId, uiState.callerNumber, labelRevision) {
        externalLabel = CallGuardLabelClient.lookup(context, uiState.callerNumber)
    }

    // 注册观察者，等 CallGuard 的"标签到了"通知。
    // 注册之后还会主动刷一次（见下方 `if (registered) labelRevision++`），
    // 因为冷启动期间的通知可能落在"还没注册"的窗口里。
    DisposableEffect(uiState.callerNumber) {
        val number = uiState.callerNumber
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                // 先作废本地缓存，否则重查会命中 60 秒 TTL 里的旧值
                CallGuardLabelClient.invalidate(number)
                labelRevision++
            }
        }
        val registered = try {
            context.contentResolver.registerContentObserver(
                CallGuardLabelClient.labelUri(), true, observer
            )
            true
        } catch (t: Throwable) {
            // CallGuard 没装 / provider 被停用：静默跳过，界面照常
            false
        }
        // 注册成功后再刷一次。冷启动时 Activity 要 1~2 秒才完成组合，
        // 如果数据恰好在这段窗口里写入并通知，那次通知会丢——
        // 补这一次查询就把窗口堵上了（有 60 秒缓存兜底，代价接近 0）。
        if (registered) {
            CallGuardLabelClient.invalidate(number)
            labelRevision++
        }
        onDispose {
            if (registered) {
                try {
                    context.contentResolver.unregisterContentObserver(observer)
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    // Dismiss the sheet when there is no longer a conference to manage, so a
    // stale `showManageSheet = true` can't re-open it when a new conference forms.
    LaunchedEffect(canManageConference) {
        if (!canManageConference) showManageSheet = false
    }

    if (showManageSheet && canManageConference) {
        ModalBottomSheet(
            onDismissRequest = { showManageSheet = false },
            sheetState = sheetState
        ) {
            ManageConferenceSheet(
                participants = uiState.conferenceParticipants,
                showSplit = showSplitInManage,
                onSplit = { call -> viewModel.split(call) },
                onHangup = { call -> viewModel.hangup(call) }
            )
        }
    }

    AppProviders {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    InCallDetails(
                        callerName = uiState.callerName,
                        callerNumber = uiState.callerNumber,
                        callerNumberLabel = uiState.callerNumberLabel,
                        status = uiState.status,
                        durationMillis = durationMillis,
                        callerImageUri = uiState.callerImageUri,
                        simInfo = uiState.simInfo,
                        isConference = canManageConference,
                        externalLabel = externalLabel,
                        lookupBusy = lookupBusy,
                        onLookup = {
                            if (!lookupBusy) {
                                lookupBusy = true
                                scope.launch {
                                    val accepted = CallGuardLabelClient
                                        .requestLookup(context, uiState.callerNumber)
                                    if (!accepted) lookupBusy = false
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(top = 32.dp)
                    )
                }

                if (hasSecondaryCall && secondaryCallerName != null && !uiState.isIncoming) {
                    SecondaryCallBanner(
                        callerName = secondaryCallerName,
                        modifier = Modifier
                            .statusBarsPadding()
                            .align(Alignment.TopCenter)
                    )
                }

            if (uiState.isIncoming) {
                IncomingCallControls(
                    onHangup = viewModel::hangup,
                    onAnswer = viewModel::answer,
                    onMessage = { viewModel.hangup(it) },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            } else {
                InCallControls(
                    isMuted = uiState.isMuted,
                    isSpeaker = uiState.isSpeaker,
                    audioRoutes = uiState.audioRoutes,
                    isHolding = uiState.isHolding,
                    canManageConference = canManageConference,
                    canMerge = canMerge,
                    canSwap = canSwap,
                    canHold = canHold,
                    showAddCall = !hasSecondaryCall,
                    canAddCall = canAddCall,
                    controlsEnabled = uiState.status != CallStatus.CONNECTING &&
                        uiState.status != CallStatus.DISCONNECTING &&
                        uiState.status != CallStatus.DISCONNECTED,
                    canHangup = uiState.status != CallStatus.DISCONNECTING &&
                        uiState.status != CallStatus.DISCONNECTED,
                    onHangup = viewModel::hangup,
                    onMute = viewModel::turnMute,
                    onSpeaker = viewModel::turnSpeaker,
                    onAudioRouteSelected = viewModel::selectAudioRoute,
                    onHold = viewModel::hold,
                    onAddCall = { viewModel.addCall(activity = context.getActivity() as Activity) },
                    onMerge = { viewModel.merge() },
                    onSwap = { viewModel.swap() },
                    onManageConference = { showManageSheet = true },
                    onDigitPress = viewModel::startDtmf,
                    onDigitRelease = viewModel::stopDtmf,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}
}

enum class OpenSection {
    ADDITIONAL_ACTIONS,
    DIALPAD
}
