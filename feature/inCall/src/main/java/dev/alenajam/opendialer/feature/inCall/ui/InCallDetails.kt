package dev.alenajam.opendialer.feature.inCall.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.alenajam.opendialer.core.common.CommonUtils
import dev.alenajam.opendialer.core.common.telecom.SimInfo
import dev.alenajam.opendialer.core.common.ui.ContactAvatar
import dev.alenajam.opendialer.core.common.ui.LocalAppIcons
import dev.alenajam.opendialer.core.common.ui.SimBadge
import dev.alenajam.opendialer.core.common.ui.contactAvatarColorKey
import dev.alenajam.opendialer.feature.inCall.R
import dev.alenajam.opendialer.core.common.ExternalLabel

@Composable
fun InCallDetails(
    callerName: String,
    callerNumber: String,
    callerNumberLabel: String,
    status: CallStatus,
    durationMillis: Long,
    callerImageUri: String? = null,
    isConference: Boolean = false,
    /** 双卡时显示 "卡 2 · 中国联通"；null 表示单卡或不显示。 */
    simInfo: SimInfo? = null,
    externalLabel: ExternalLabel? = null,
    /** 正在等 CallGuard 返回重查结果（按钮显示「查询中…」并禁用）。 */
    lookupBusy: Boolean = false,
    /** 点「用付费接口重查」。为 null 表示不提供这个按钮。 */
    onLookup: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    showCallerImage: Boolean = true,
    useCompactCallerText: Boolean = false,
) {
    val icons = LocalAppIcons.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        val displayName = callerName.ifBlank { callerNumber.ifBlank { "Unknown" } }

        Text(
            text = if (status == CallStatus.ACTIVE) {
                CommonUtils.getDurationTimeString(durationMillis)
            } else {
                getCallStatusLabel(status)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(if (useCompactCallerText) 0.dp else 8.dp))

        Text(
            text = displayName,
            style = if (useCompactCallerText) {
                MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 24.sp,
                    lineHeight = 28.sp,
                )
            } else {
                MaterialTheme.typography.headlineLarge
            },
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        if (callerName.isNotBlank() && callerNumber.isNotBlank() && callerName != callerNumber) {
            Text(
                text = if (callerNumberLabel.isBlank()) {
                    callerNumber
                } else {
                    stringResource(R.string.caller_number_subtitle, callerNumberLabel, callerNumber)
                },
                style = if (useCompactCallerText) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (simInfo != null) {
            Spacer(modifier = Modifier.height(6.dp))
            SimBadge(info = simInfo, showCarrier = true)
        }

        if (externalLabel != null && !externalLabel.isBlank) {
            Spacer(modifier = Modifier.height(6.dp))
            ExternalLabelLines(externalLabel = externalLabel)
        }

        // 「用付费接口重查」只在完整接听界面出现（横幅走另一套布局，没有按钮）。
        // CallGuard 用 lookup=1 告诉我们"现在点有意义"——它知道有没有 Key、会不会自动查。
        if (externalLabel?.canLookup == true && onLookup != null) {
            Spacer(modifier = Modifier.height(2.dp))
            TextButton(onClick = onLookup, enabled = !lookupBusy) {
                Text(
                    text = stringResource(
                        if (lookupBusy) R.string.callguard_lookup_busy
                        else R.string.callguard_lookup_recheck
                    ),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        if (showCallerImage) {
            Spacer(modifier = Modifier.height(80.dp))

            ContactAvatar(
                name = callerName.takeIf { it.isNotBlank() && it != callerNumber },
                photoUri = callerImageUri,
                colorKey = contactAvatarColorKey(callerName, callerNumber),
                fallbackIconModifier = Modifier.size(72.dp),
                avatarIcon = if (isConference) icons.conference else null,
                initialTextStyle = MaterialTheme.typography.displayLarge,
                modifier = Modifier.size(176.dp)
            )
        }
    }
}

@Composable
fun getCallStatusLabel(status: CallStatus): String = when (status) {
    CallStatus.RINGING -> stringResource(R.string.call_ringing_title)
    CallStatus.CONNECTING -> stringResource(R.string.call_connecting_title)
    CallStatus.HOLDING -> stringResource(R.string.call_holding_title)
    CallStatus.DIALING -> stringResource(R.string.call_dialing_title)
    CallStatus.DISCONNECTING -> stringResource(R.string.call_disconnecting_title)
    CallStatus.DISCONNECTED -> stringResource(R.string.call_disconnected_title)
    else -> ""
}

/**
 * 渲染 CallGuard 送来的附加信息。
 *
 * 第一行通常是由号段库推出来的归属地（"北京 移动"），用次要色；
 * 后续行通常是标记（"被 3 家标记为骚扰"），按 [ExternalLabel.tone] 着色，
 * 颜色全部取自 Material You 主题，不硬编码。
 */
@Composable
private fun ExternalLabelLines(externalLabel: ExternalLabel) {
    val toneColor = when (externalLabel.tone) {
        ExternalLabel.Tone.BAD -> MaterialTheme.colorScheme.error
        ExternalLabel.Tone.WARN -> MaterialTheme.colorScheme.tertiary
        ExternalLabel.Tone.GOOD -> MaterialTheme.colorScheme.primary
        ExternalLabel.Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // 归属地只是地理信息，没有褒贬，恒用次要色
        if (externalLabel.region.isNotBlank()) {
            Text(
                text = externalLabel.region,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
        // 查询失败：不能显示成"没有标签"，否则用户会以为号码是干净的
        if (externalLabel.failed) {
            Text(
                text = stringResource(R.string.callguard_lookup_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
        // 标签行按 tone 着色，包括只有一行的情况
        externalLabel.lines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = toneColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
        // 「免费接口 · 3 天前」：让用户知道这个结论的来源和新旧
        val meta = externalLabel.metaText()
        if (meta != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}

/**
 * 第二行小字：来源 + 查询时间。
 *
 * 时间只在通话界面停留的一瞬间算一次，不会自己走字——足够了，
 * 这里要表达的是"这条结论有多旧"，不是秒表。
 */
@Composable
private fun ExternalLabel.metaText(): String? {
    if (queriedAt <= 0L) return null
    val sourceLabel = when (source) {
        "kit9" -> stringResource(R.string.callguard_source_free)
        "tmini" -> stringResource(R.string.callguard_source_paid)
        "directory" -> stringResource(R.string.callguard_source_builtin)
        else -> ""
    }
    val age = (System.currentTimeMillis() - queriedAt).coerceAtLeast(0L)
    val ago = when {
        age < HOUR_MS -> stringResource(R.string.callguard_ago_now)
        age < DAY_MS -> stringResource(R.string.callguard_ago_hours, (age / HOUR_MS).toInt())
        else -> stringResource(R.string.callguard_ago_days, (age / DAY_MS).toInt())
    }
    return if (sourceLabel.isEmpty()) ago
    else stringResource(R.string.callguard_meta, sourceLabel, ago)
}

private const val HOUR_MS = 60L * 60 * 1000
private const val DAY_MS = 24L * HOUR_MS
