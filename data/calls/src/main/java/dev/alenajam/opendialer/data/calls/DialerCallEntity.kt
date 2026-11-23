package dev.alenajam.opendialer.data.calls

data class DialerCallEntity(
    val id: Int,
    val number: String?,
    val name: String?,
    val date: Long,
    val duration: Long,
    val type: Int,
    val isNew: Int,
    val photoUri: String?,
    val countryIso: String? = null,
    val label: String?,
    val lookupUri: String?,
    val normalizedNumber: String? = null,
    val formattedNumber: String? = null,
    val geoDescription: String? = null,
    val photoId: Long? = null,
    val postDialDigits: String? = null,
    val matchedNumber: String? = null,
    val numberType: Int? = null,
    val isVoicemailNumber: Boolean = false,
    /** `CallLog.Calls.BLOCK_REASON`：1=CallGuard 拦的，3=系统黑名单。非被拦记录为 0。 */
    val blockReason: Int = 0,
    /** `CallLog.Calls.PHONE_ACCOUNT_ID`：电话账户就是 subscriptionId，用来标出走的哪张卡。 */
    val phoneAccountId: String? = null,
)
