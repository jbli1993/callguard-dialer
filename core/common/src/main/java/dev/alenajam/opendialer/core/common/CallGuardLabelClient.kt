package dev.alenajam.opendialer.core.common

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CallGuard 送来的来电身份信息（号段归属地 / 在线标记）。
 *
 * 这是**可选**的附加信息：没有装 CallGuard、装的是旧版本、或者用户在 CallGuard 里
 * 关掉了对外接口，[CallGuardLabelClient.lookup] 都会安静地返回 null，
 * 界面照常按系统默认显示。
 *
 * 放在 `core:common` 而不是 `feature:inCall`，因为通话界面和通话记录列表都要用它。
 */
data class ExternalLabel(
    /** 号段归属地全称，例如「北京 移动」。恒为中性色。 */
    val region: String = "",
    /** 号段归属地的短版本，例如「北京」。列表里空间有限时用。 */
    val regionShort: String = "",
    /** 标签行：企业认证、被几家平台标记等。按 [tone] 着色。 */
    val lines: List<String> = emptyList(),
    val tone: Tone = Tone.NEUTRAL,
    /** 标签来源：`kit9`（免费）/ `tmini`（付费）/ `directory`（CallGuard 内置号码表）；空表示未知。 */
    val source: String = "",
    /** 查询时间（毫秒时间戳）；0 表示未知。 */
    val queriedAt: Long = 0L,
    /** 上一次在线查询失败。此时 [lines] 通常为空，界面应显示失败提示而不是"干净"。 */
    val failed: Boolean = false,
    /** CallGuard 说「点查询会有用」——有付费 Key，且不会自动查。 */
    val canLookup: Boolean = false,
    /** 这个号码被拦过（CallGuard 的本地拦截日志里有记录）。 */
    val blocked: Boolean = false,
    /** 简短原因：`命中地区 新疆 乌鲁木齐` / `未知号段` / `国际来电` …；空表示不知道。 */
    val blockReason: String = ""
) {
    enum class Tone { NEUTRAL, GOOD, WARN, BAD }

    val isBlank: Boolean get() = region.isBlank() && lines.isEmpty() && !failed

    /**
     * 横幅（heads-up）用的摘要。
     *
     * 优先给"被标记"这条——它才是决定接不接的依据；没有标记才退回归属地。
     * 去掉 ✅/⚠ 前缀，因为自定义布局里已经有颜色和位置区分。
     */
    fun bannerSummary(): String {
        val mark = lines.firstOrNull()?.trim()
        if (!mark.isNullOrEmpty()) {
            return mark.removePrefix("✅").removePrefix("⚠").trim()
        }
        return region.trim()
    }

    /**
     * 通话记录列表用的极短摘要，一行放得下。
     *
     * 优先级链（和国产 ROM 一致）：
     * 1. 精确分类——`被 2 家标记 · 骚扰电话 ×15、推销 ×1` → `骚扰电话`
     * 2. 是否骚扰——只有粗略标记时给 `疑似骚扰`
     * 3. 空字符串——调用方自己用 [regionShort] 兜底
     *
     * 列表一行大约只有 18 个中文字的宽度，所以这里必须压到 4 字上下。
     */
    fun listSummary(): String {
        if (lines.isEmpty()) {
            return when (tone) {
                Tone.BAD, Tone.WARN -> "疑似骚扰"
                else -> ""
            }
        }
        // 标记行（⚠）优先于认证行（✅）：一个号码既被认证又被标成诈骗时，
        // 用户最该看到的是"诈骗电话"，不是"某某企业"。
        val mark = lines.firstOrNull { it.trimStart().startsWith("⚠") } ?: lines.first()
        val body = mark.trim().removePrefix("✅").removePrefix("⚠").trim()
        // "被 2 家标记 · 骚扰电话 ×15、推销 ×1" → "骚扰电话"
        val afterCount = body.substringAfter("· ", body)
        return afterCount.substringBefore("、").substringBefore(" ×").trim()
    }
}

/**
 * 读取 CallGuard 的标签。
 *
 * <h3>为什么要跨应用</h3>
 * 拨号器（本应用）和 CallGuard 是两个独立的包，各自升级、各自演进。
 * 唯一的耦合点是 [ExternalLabel] 的**文本协议**，不是代码依赖：
 *
 * <pre>
 * v1
 * region=北京 移动
 * region_short=北京
 * line1=✅ 美团外卖
 * line2=⚠ 被 2 家标记 · 骚扰电话 ×15、推销 ×1
 * tone=warn
 * source=kit9
 * queried_at=1794746345110
 * lookup=1
 * </pre>
 *
 * <ul>
 *   <li>第一行必须是 `v1`；版本不认识就整体忽略，不会显示半截信息。</li>
 *   <li>其余是 `key=value`，按第一个 `=` 切分，顺序无关，未知 key 忽略。</li>
 *   <li>`lineN` 从 1 开始连续，遇到空即停止。</li>
 *   <li>`tone` 可选：neutral / good / warn / bad，用于着色。</li>
 *   <li>`source` 可选：kit9 / tmini / directory，显示成「免费接口 · 3 天前」。</li>
 *   <li>`queried_at` 可选：毫秒时间戳。</li>
 *   <li>`failed=1`：上次查询失败，没有 `lineN`，显示「免费查询失败」。</li>
 *   <li>`lookup=1`：显示「用付费接口重查」按钮。</li>
 *   <li>`blocked=1` + `block_reason=…`：这个号码被拦过，以及简短原因（通话记录用）。</li>
 * </ul>
 *
 * 这样 CallGuard 以后要加字段（比如 `badge=`、`source=`），本应用不改也能继续工作；
 * 反过来本应用升级也不会要求 CallGuard 同步发版。
 */
object CallGuardLabelClient {

    private const val TAG = "CallGuardLabel"
    private const val AUTHORITY = "com.callguard.app.provider"
    private const val METHOD = "label"
    private const val METHOD_LOOKUP = "lookup"
    private const val BUNDLE_KEY = "text"
    private const val BUNDLE_KEY_ACCEPTED = "accepted"
    private const val SUPPORTED_VERSION = "v1"

    /** 最多显示几行——再多会挤掉头像和状态行。 */
    private const val MAX_LINES = 3

    /** 同一通电话里界面会重组很多次，没必要每次都跨进程查。 */
    private const val CACHE_TTL_MS = 60_000L
    private const val CACHE_SIZE = 32

    private val labelUri: Uri = Uri.parse("content://$AUTHORITY/label")

    /**
     * 供界面注册 ContentObserver 用。CallGuard 联网查完会把结果写进缓存并
     * `notifyChange` 这个 URI，正在显示的界面据此刷新。
     */
    fun labelUri(): Uri = labelUri

    private class Entry(val at: Long, val label: ExternalLabel?)
    private val cache = object : LinkedHashMap<String, Entry>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > CACHE_SIZE
    }

    /**
     * 查询一个号码的标签。任何异常都返回 null —— 界面绝不能因为查不到标签而出问题。
     */
    suspend fun lookup(context: Context, number: String): ExternalLabel? =
        withContext(Dispatchers.IO) {
            val digits = number.trim()
            if (digits.isEmpty()) return@withContext null

            cached(digits)?.let { return@withContext it }

            val result = try {
                val bundle = context.contentResolver.call(labelUri, METHOD, digits, null)
                parse(bundle?.getString(BUNDLE_KEY))
            } catch (t: Throwable) {
                // IllegalArgumentException：CallGuard 没装或 provider 被禁用
                Log.d(TAG, "lookup $digits unavailable: ${t.javaClass.simpleName} ${t.message}")
                null
            }

            synchronized(cache) {
                cache[digits] = Entry(System.currentTimeMillis(), result)
            }
            result
        }

    /**
     * 请求 CallGuard 重新查一次（跳过它的 30 天缓存）。
     *
     * 这是**异步受理**：CallGuard 立即返回"收到"，真正的网络查询在后台跑，
     * 查完写缓存并 `notifyChange`，本应用的 [labelUri] 观察者会触发刷新。
     *
     * @return 是否受理。CallGuard 没装 / 关掉了对外接口 / 调用方不是本应用时为 false。
     */
    suspend fun requestLookup(context: Context, number: String): Boolean =
        withContext(Dispatchers.IO) {
            val digits = number.trim()
            if (digits.isEmpty()) return@withContext false
            try {
                val bundle = context.contentResolver.call(labelUri, METHOD_LOOKUP, digits, null)
                bundle?.getBoolean(BUNDLE_KEY_ACCEPTED) == true
            } catch (t: Throwable) {
                Log.d(TAG, "lookup request $digits unavailable: ${t.javaClass.simpleName}")
                false
            }
        }

    /**
     * 丢弃某个号码的本地缓存。
     *
     * CallGuard 通知"标签变了"时必须调用，否则重查会命中 60 秒缓存、拿回旧值。
     */
    fun invalidate(number: String) {
        val digits = number.trim()
        if (digits.isEmpty()) return
        synchronized(cache) { cache.remove(digits) }
    }

    private fun cached(digits: String): ExternalLabel? = synchronized(cache) {
        val entry = cache[digits] ?: return null
        if (System.currentTimeMillis() - entry.at > CACHE_TTL_MS) {
            cache.remove(digits)
            null
        } else {
            entry.label
        }
    }

    /**
     * 解析协议文本。返回 null 表示"这段内容不可用"，调用方应保持原样。
     */
    internal fun parse(raw: String?): ExternalLabel? {
        if (raw.isNullOrBlank()) return null

        val rows = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return null
        if (rows[0] != SUPPORTED_VERSION) {
            Log.d(TAG, "unsupported protocol version: ${rows[0]}")
            return null
        }

        val fields = LinkedHashMap<String, String>()
        for (index in 1 until rows.size) {
            val eq = rows[index].indexOf('=')
            if (eq <= 0) continue
            val key = rows[index].substring(0, eq).trim().lowercase()
            fields[key] = rows[index].substring(eq + 1).trim()
        }

        val lines = ArrayList<String>(MAX_LINES)
        for (index in 1..MAX_LINES) {
            val value = fields["line$index"]
            if (value.isNullOrBlank()) break
            lines.add(value)
        }
        val region = fields["region"].orEmpty()
        val failed = fields["failed"] == "1"
        if (region.isBlank() && lines.isEmpty() && !failed) return null
        val tone = when (fields["tone"]?.lowercase()) {
            "good" -> ExternalLabel.Tone.GOOD
            "warn" -> ExternalLabel.Tone.WARN
            "bad" -> ExternalLabel.Tone.BAD
            else -> ExternalLabel.Tone.NEUTRAL
        }

        return ExternalLabel(
            region = region,
            regionShort = fields["region_short"].orEmpty(),
            lines = lines,
            tone = tone,
            source = fields["source"].orEmpty(),
            queriedAt = fields["queried_at"]?.toLongOrNull() ?: 0L,
            failed = failed,
            canLookup = fields["lookup"] == "1",
            blocked = fields["blocked"] == "1",
            blockReason = fields["block_reason"].orEmpty()
        )
    }
}
