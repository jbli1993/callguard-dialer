package dev.alenajam.opendialer.data.calls

import java.io.Serializable
import java.util.Date

class DetailCall(
    val id: Int,
    val type: CallType,
    val date: Date,
    val duration: Long,
    /** 这一通具体走的哪张卡（`PhoneAccountHandle.id`）。历史记录里同一号码可能换卡打过。 */
    val phoneAccountId: String? = null,
) : Serializable {
    companion object {
        fun map(call: DialerCallEntity): DetailCall? {
            val type = CallType.values().find { t -> t.value == call.type }

            type?.let {
                return DetailCall(
                    id = call.id,
                    type = it,
                    date = Date(call.date),
                    duration = call.duration,
                    phoneAccountId = call.phoneAccountId,
                )
            }
            return null
        }
    }
}