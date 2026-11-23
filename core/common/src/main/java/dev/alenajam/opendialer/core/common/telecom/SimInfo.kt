package dev.alenajam.opendialer.core.common.telecom

import android.content.Context
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager

/**
 * 一张在用的 SIM 卡。
 *
 * <p>用来把通话记录 / 通话界面里的 `PhoneAccountHandle.id`（对电话账户来说就是
 * `subscriptionId`）翻译成用户看得懂的东西：卡槽、运营商名、系统给的颜色。
 */
data class SimInfo(
    val subscriptionId: Int,
    val slotIndex: Int,
    /** 用户给卡起的名字优先，其次运营商名；都没有就为 null（界面回落成 "SIM n"）。 */
    val carrier: String?,
    /** `SubscriptionInfo.iconTint`，系统设置里给这张卡选的颜色。 */
    val color: Int?,
)

object SimInfoResolver {
    /** 所有在用的卡（没权限 / 没插卡时为空）。 */
    fun all(context: Context): List<SimInfo> = subscriptions(context).map { it.toSimInfo() }

    /** 通过 `PhoneAccountHandle.id` 找对应的卡；找不到返回 null。 */
    fun resolve(context: Context, phoneAccountId: String?): SimInfo? {
        val id = phoneAccountId?.toIntOrNull() ?: return null
        return all(context).firstOrNull { it.subscriptionId == id }
    }

    /**
     * 只在**双卡**时才返回，单卡返回 null。
     *
     * <p>单卡手机上每通电话都挂一个 "SIM 1" 徽标纯属噪音，所以统一在入口处挡掉。
     */
    fun resolveForBadge(context: Context, phoneAccountId: String?): SimInfo? {
        val id = phoneAccountId?.toIntOrNull() ?: return null
        val all = all(context)
        if (all.size < 2) return null
        return all.firstOrNull { it.subscriptionId == id }
    }

    private fun subscriptions(context: Context): List<SubscriptionInfo> = runCatching {
        context.getSystemService(SubscriptionManager::class.java)
            ?.activeSubscriptionInfoList
            .orEmpty()
    }.getOrDefault(emptyList())

    private fun SubscriptionInfo.toSimInfo(): SimInfo = SimInfo(
        subscriptionId = subscriptionId,
        slotIndex = simSlotIndex,
        carrier = displayName?.toString()?.takeIf { it.isNotBlank() }
            ?: carrierName?.toString()?.takeIf { it.isNotBlank() },
        color = iconTint.takeIf { it != 0 },
    )
}
