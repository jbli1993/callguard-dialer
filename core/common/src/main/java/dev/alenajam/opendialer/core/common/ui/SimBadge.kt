package dev.alenajam.opendialer.core.common.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.alenajam.opendialer.core.common.R
import dev.alenajam.opendialer.core.common.telecom.SimInfo
import dev.alenajam.opendialer.core.common.telecom.SimInfoResolver

/**
 * 双卡时才返回 `subscriptionId -> SimInfo` 的映射；单卡返回空 map。
 *
 * <p>列表里每行都去查一次 `SubscriptionManager` 太浪费，所以在屏幕级别取一次复用。
 */
@Composable
fun rememberSimInfos(): Map<Int, SimInfo> {
    val context = LocalContext.current
    val infos = remember(context) { SimInfoResolver.all(context) }
    return remember(infos) {
        if (infos.size > 1) infos.associateBy { it.subscriptionId } else emptyMap()
    }
}

/**
 * "● SIM 2" 小徽标：圆点用系统分配给这张卡的颜色，文字用次要色。
 *
 * @param showCarrier 为 true 时显示成 "SIM 2 · 中国联通"，用于通话界面这种空间充足的地方。
 */
@Composable
fun SimBadge(
    info: SimInfo,
    modifier: Modifier = Modifier,
    showCarrier: Boolean = false,
    /** 列表里用紧凑模式：只显示 "● 2"，避免挤掉号码。 */
    compact: Boolean = false,
) {
    val dotColor = info.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val slot = info.slotIndex + 1
    val shortLabel = stringResource(R.string.sim_badge, slot)
    val text = when {
        compact -> slot.toString()
        showCarrier && info.carrier != null ->
            stringResource(R.string.sim_badge_with_carrier, slot, info.carrier)
        else -> shortLabel
    }
    Row(
        modifier = modifier.then(
            if (compact) {
                Modifier.semantics { contentDescription = shortLabel }
            } else {
                Modifier
            }
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
