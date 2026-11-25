# CallGuard 补丁说明

这是 [oxcened/opendialer](https://github.com/oxcened/opendialer) 的本地 fork，唯一目的是让通话界面显示 CallGuard 的来电身份信息（号段归属地 / 骚扰标记）。

改动刻意压到最小，便于跟进上游：

## 改了哪些文件

| 文件 | 类型 | 改动 |
|---|---|---|
| `app/src/main/AndroidManifest.xml` | 修改 | 加 `<queries><provider android:authorities="com.callguard.app.provider"/></queries>` |
| `core/common/.../CallGuardLabelClient.kt` | **新增** | 跨进程查询 + 解析文本协议 + 60 秒内存缓存 + `requestLookup()` |
| `feature/inCall/.../ui/InCallDetails.kt` | 修改 | 新增可选参数 `externalLabel` / `lookupBusy` / `onLookup`；`ExternalLabelLines` 渲染失败态、来源与时间、付费重查按钮 |
| `feature/inCall/.../ui/InCallScreen.kt` | 修改 | 查询 + `ContentObserver` 监听标签变更 + 按钮的「查询中」状态 |
| `feature/inCall/.../service/CallNotificationManager.kt` | 修改 | 自定义横幅布局 + 标签异步补全 + 通知所有权判定 |
| `feature/inCall/.../service/MissedCallNotificationReceiver.kt` | **新增** | 接收 Telecom 未接广播，线程内重建 CallLog 通知堆栈 |
| `feature/inCall/.../service/MissedCallNotifications.kt` | **新增** | AOSP 风格固定 ID + summary/child 稳定 tag；标记已读、清理、过滤 self-managed |
| `feature/inCall/.../service/MissedCallDismissReceiver.kt` | **新增** | 未接通知删除/单条清理的接收器 |
| `feature/inCall/.../service/CallManager.kt` | 修改 | `CallEvent.MissedCall` 增加 `selfManaged` |
| `feature/inCall/.../service/CallsHandler.kt` | 修改 | 生成 MissedCall 事件时带上 `PROPERTY_SELF_MANAGED` |
| `app/src/main/AndroidManifest.xml` | 修改 | 注册未接通知 receiver 和 dismiss receiver |
| `feature/inCall/src/main/res/values{,-zh}/strings.xml` | 修改 | 按钮 / 未接汇总 / 失败 / 来源 / 相对时间文案 |
| `res/layout/notification_call_custom.xml` | **新增** | 折叠 / 横幅布局（48dp）：两行文字 + 48dp 红绿圆按钮 |
| `res/layout/notification_call_custom_large.xml` | **新增** | 展开态布局（52dp 按钮 + 6dp 上下 padding），避免展开后过空 |
| `res/drawable/ic_call_answer.xml`</br>`ic_call_decline.xml`</br>`bg_call_answer.xml`</br>`bg_call_decline.xml` | **新增** | 接听/挂断图标（挂断为电话图标转 135°）与圆形按钮背景 |

没有改动：`InCallServiceImpl`、`OngoingCall`、`CallContactResolver`、任何 Hilt 绑定、任何 Gradle 配置。

这些改动集中在 `feature/inCall` 和一层 manifest/资源；合并上游时可按文件重放。

## 时序问题：为什么需要 ContentObserver

这是这套方案最容易出问题的地方，值得单独写清楚。

```
t=0ms      来电
t≈0-250ms  CallScreeningService.onScreenCall()      ← CallGuard 在这里（最早）
t≈13-250ms Telecom 调 InCallService.onCallAdded()
t≈300ms    InCallActivity 起来，第一次查 provider    ← 必然是空的
t=1-6s     CallGuard 的在线查询才回来，写缓存
```

拨号器**起得比数据早得多**。所以第一次查询一定拿不到在线标签，只有号段归属地（那个是离线的，随时有）。

如果只查一次，界面就永远停在"只有归属地"，用户等了 5 秒也不会变——这就是"写晚了界面不刷新"。

解法是 CallGuard 写完缓存后 `notifyChange`，本应用用 `ContentObserver` 收：

```kotlin
DisposableEffect(uiState.callerNumber) {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { labelRevision++ }
    }
    val registered = try {
        context.contentResolver.registerContentObserver(CallGuardLabelClient.labelUri(), true, observer)
        true
    } catch (t: Throwable) { false }
    // 注册后补一次：冷启动窗口里错过的那条通知靠它补上
    if (registered) labelRevision++
    onDispose { if (registered) context.contentResolver.unregisterContentObserver(observer) }
}
```

`labelRevision` 是 `LaunchedEffect` 的 key 之一，自增就触发重查。

界面表现：**先出现归属地，1~6 秒后自己补上标记**，用户不用做任何操作。

### 那个 `if (registered) labelRevision++` 不是多余的

一开始我判断它没必要："`onEnter` 在主线程同步跑完，而 `lookup` 会切到 IO 线程，IPC 一定发生在注册之后"。

实测打脸：Activity **冷启动要 1~2 秒**才完成第一次组合。如果数据恰好在这段窗口里写入并发出通知，那次通知就丢了，界面停在只有归属地的状态。

补这一次查询彻底堵上窗口。代价接近零（有 60 秒缓存兜底，且只在换号码时发生）。

### 收到通知必须先作废本地缓存

这是第三个坑，也是最隐蔽的一个。

`CallGuardLabelClient` 有 60 秒内存缓存，避免同一通电话里反复 IPC。但 `notifyChange` 触发重查时会**直接命中这个缓存，拿回旧值**——界面看起来"收到通知了但没变"。

```kotlin
override fun onChange(selfChange: Boolean) {
    CallGuardLabelClient.invalidate(number)   // ← 关键
    labelRevision++
}
```

不加这一行的症状特别迷惑：日志显示查询发生了、值也对了，但界面纹丝不动。而且它**时灵时不灵**——如果两次数据间隔超过 60 秒，缓存刚好过期，看起来就"正常"了。我第一次验证"成功"就是这么来的巧合。

### 不用轮询

轮询（每秒重试 N 次）也能工作，但：

- 没有数据时一直在做无用的跨进程调用
- 数据到了还要等下一个轮询周期
- 通话音界面本来就该尽量少耗电

`notifyChange` 是推的，延迟接近零，且没有数据时完全不耗。

## 通话界面的「用付费接口重查」

免费查询（kit9）由 CallGuard 自动做，所以界面不需要"查一下"的入口。唯一值得手动触发的是**付费接口**（Tmini，9 家平台）——当用户填了 Key、开了付费、但查询时机不是「自动」时。

按钮的显示条件完全由 CallGuard 决定，本应用只看协议里的 `lookup=1`：

```
v1
region=新疆 乌鲁木齐 移动
line1=⚠ 被 2 家标记 · 骚扰电话 ×15
tone=warn
source=kit9
queried_at=1794746345110
lookup=1
```

本应用不需要知道有没有 Key、查询时机是什么——那是 CallGuard 的设置，不该跨进程泄漏到 UI 逻辑里。

### 只加在全屏，横幅不加

横幅（heads-up）只有两行文字 + 红绿两个按钮的空间，塞交互按钮既挤又容易误触。所以按钮只在 `InCallDetails` 里，`CallNotificationManager` 的自定义布局完全不动。

### 点了之后

```kotlin
suspend fun requestLookup(context: Context, number: String): Boolean =
    withContext(Dispatchers.IO) {
        val bundle = context.contentResolver.call(labelUri, "lookup", digits, null)
        bundle?.getBoolean("accepted") == true
    }
```

**异步受理**：CallGuard 只回"收到"，真正的网络查询在它自己的后台线程跑，查完写缓存 + `notifyChange`——本应用已有的 `ContentObserver` 会触发刷新。所以点击后不需要轮询、不需要等返回值。

按钮在等待期间变「查询中…」并禁用；`LaunchedEffect(labelRevision)` 在数据到达时收起，另有 15 秒兜底。

### 四态

| 状态 | 内容 | 按钮 |
|---|---|---|
| 免费结果 | 归属地 / 标签 / `免费接口 · 刚刚` | 「用付费接口重查」 |
| 付费结果 | 同上（`付费接口 · …`） | 无 |
| 查询中 | 原内容保留，按钮变「查询中…」 | 禁用 |
| **查询失败** | 归属地 / **`免费查询失败`** / `免费接口 · 刚刚` | **无** |

失败态是最重要的一条：`failed=1` 时**不能**显示成"没有标签"。两者在界面上长得一样，但含义完全相反（"查过了，干净"vs"没查到，不知道"）。

失败不加重试按钮——免费都失败了，点第二次只是把失败再看一遍。CallGuard 那边失败缓存不设 TTL，下一次来电自动重查。

### 时间与来源

`source` + `queried_at` 渲染成一行小字（`labelSmall`，70% 透明度）：

```
免费接口 · 3 天前
```

时间只在组合时算一次（`刚刚` / `N 小时前` / `N 天前`），不做实时走动——这里要表达的是"这条结论有多旧"，不是秒表。

## 通话记录列表显示标签

位置：`feature/calls/.../CallsScreen.kt` 的 `CallRow`。

第二行按优先级取**一个**信息，不放两个（列表一行只有约 18 个中文字的宽度）：

| 号码类型 | 第二行 | 颜色 |
|---|---|---|
| 通讯录里的人 | 电话类型（Mobile / 总机） | 正常 |
| 陌生 + 有标签 | **标签**（`诈骗电话` / `快递`） | 按 `tone` |
| 陌生 + 只有归属地 | **归属地短名**（`郑州` / `Qujing`） | 正常 |
| 什么都没有 | 空（只有时间） | 正常 |

```kotlin
val callLabel = when {
    isNumberBlocked -> "已拦截"
    call.isContactSaved() -> 电话类型
    else -> guardLabel?.listSummary()?.ifBlank { guardLabel?.regionShort.orEmpty() }.orEmpty()
}
```

`listSummary()` 里有个细节：**标记行（`⚠`）优先于认证行（`✅`）**。一个号码既被企业认证又被标成诈骗时，用户最该看到的是"诈骗电话"——第一版取 `lines[0]` 结果显示了"美团外卖"，实测才发现。

查询是每个列表项一次 `LaunchedEffect`，有 60 秒内存缓存兜底，滚动时不会反复跨进程。

### 被拦截的通话也显示（对齐 Google 拨号器）

上游的查询把 `BLOCKED_TYPE` 过滤掉了：

```kotlin
// CallsData.kt —— 上游
/** Filter out blocked calls and non-video Duo calls. */
${Calls.TYPE} != ${Calls.BLOCKED_TYPE}
```

结果是被拦的通话**完全不可见**（不管是系统黑名单拦的还是 CallGuard 拦的）。Google 拨号器显示它们，所以这里也对齐了：

- `CallsData.kt`：只保留 Duo 非视频通话的过滤；projection 加 `BLOCK_REASON`
- `CallsScreen.kt`：`call.type == CallType.BLOCKED` → 显示**具体原因**

```
1390-000-3333      🚫 系统黑名单 · 0分钟前            ← block_reason=3
1390-000-8888      🚫 命中地区 新疆 乌鲁木齐 · 2分钟前   ← block_reason=1 + provider 的 block_reason
```

原因的来源分两层：

| 谁拦的 | 从哪来 |
|---|---|
| 系统黑名单 | `call_log.block_reason == 3` → 直接写「系统黑名单」 |
| CallGuard | `block_reason == 1`，但"按地区还是按号段"只在 CallGuard 的拦截日志里 → 通过 provider 新增的 `blocked` / `block_reason` 字段带出来 |

顺手修掉的：以前 `isNumberBlocked`（号码**现在**在不在系统名单）优先级最高，会把**历史**普通通话也标成「已屏蔽」；
现在只看 `call.type`，号码的当前状态只影响头像上的小标记。

长原因（比如 `命中地区 新疆 乌鲁木齐`）在列表里用 `Modifier.basicMarquee()` 滚动：1.5 秒后开始、循环，
**时间固定在右侧不跟着滚**，所以不会再出现 `… · 8 hr. ago` 被截掉时间的情况。

**用词统一**：上游的中文翻译是「屏蔽」（Google Phone 的译法，英文 `Blocked`），而 CallGuard 一直叫「拦截」。三个 `values-zh` 里的 12 条全部改成「拦截」：`blocked` / `blocked_call` / `blockThisCaller` /
`unblockThisCaller` / `block_confirmation_title` / `manageBlockedNumbers` 等。英文不动（`Blocked` 本来就对）。

「未接来电」筛选仍然只含 `MISSED/REJECTED`，被拦的不算未接。

**不要用"自己写一条通话记录"来绕过隐藏**：Telecom 已经写了 `type=6`（`block_reason` 1=CallGuard / 3=系统名单），再写一条就是重复记录，写成 `MISSED` 还会污染系统角标和备份。

## 通知归属：避免系统兜底和拨号盘双条

同一通电话只能有一个通知 owner。`CallNotificationManager` 现在做三层判定：

1. **只有默认拨号盘发通话通知**。`TelecomManager.defaultDialerPackage != packageName` 时直接不发，交给系统/另一个拨号盘。
2. **未接来电只在 Telecom 会把广播交给本应用时发**。检查默认拨号盘角色，并检查 `SHOW_MISSED_CALLS_NOTIFICATION` receiver 是否可解析；不可解析时事件路径和 receiver 都放弃，让 Telecom 发自己的 `TelecomMissedCalls`。
3. **self-managed 通话**：拨号盘不发任何来电/通话中通知；无冲突时由 VoIP App 自己显示，另一条 ConnectionService 有不可保持通话时由 Telecom 的系统兜底显示。self-managed 未接也全部过滤。

未接通知现在按 AOSP Dialer 的方式工作：

- `CallLog` 里 `NEW=1 / TYPE=MISSED / IS_READ!=1` 是唯一数据源
- 固定 `NOTIFICATION_ID = 1002`
- 汇总通知用固定 tag，每通未接用 `CallLog._ID` 做稳定 tag
- group summary + 每通 child
- 事件路径和 Telecom receiver 都调用同一个 `refresh()`；重复执行只会覆盖同一 tag，不会多出一条
- `count=0` 会把所有未接标记已读并清空整组
- 单条删除只标记该 CallLog 记录已读；最后一条删除后汇总通知也消失
- 进程被杀后 Telecom receiver 重新查询 CallLog 并覆盖原 tag，已实际验证不会重复

> 这也消除了之前“事件路径 + receiver 兜底”在服务解绑瞬间可能各发一条的竞态。

## 横幅显示标签：用 RemoteViews 自定义

非全屏模式下来电只弹 heads-up 横幅，用户不点开就看不到任何信息。所以横幅本身必须能显示标签。

### 先试 CallStyle：全都不行

`Notification.CallStyle` 的 heads-up 模板只有四个固定槽位：

```
person.name  ·  appName  ·  time
callType（「来电」/「通话中」）
[decline] [accept]
```

实测：

| 尝试 | 结果 |
|---|---|
| `person.name` 拼标签 | 只放得下 **3 个汉字**，`美团外卖` → `美团外...` |
| `setSubText(label)` | **被完全忽略**。`dumpsys` 里 `android.subText=String (美团外卖)` 确实存在，但横幅一个字都不变 |
| `setContentText` / `setContentTitle` | CallStyle 会覆盖，无效 |
| 去掉应用名 | `appName` 由系统填充，无 API 可改 |

### 改用自定义布局

放弃 CallStyle，用 `RemoteViews` 自己画：

```kotlin
val collapsed = RemoteViews(context.packageName, R.layout.notification_call_custom)
val expanded = RemoteViews(context.packageName, R.layout.notification_call_custom_large)
listOf(collapsed, expanded).forEach { views ->
    views.setTextViewText(R.id.call_custom_title, caller)      // 号码
    views.setTextViewText(R.id.call_custom_subtitle, subtitle) // 归属地 / 标签
    views.setOnClickPendingIntent(R.id.call_custom_decline, declineIntent)
    views.setOnClickPendingIntent(R.id.call_custom_answer, acceptIntent)
}

builder.setCustomContentView(collapsed)
builder.setCustomBigContentView(expanded)
builder.setCustomHeadsUpContentView(collapsed)
```

布局是「左边两行文字 + 右边红/绿圆按钮」，按钮用 `oval` shape 背景 + 电话图标（挂断版旋转 135°）。

效果：

```
[📞] 13800138150          [🔴]  [🟢]  [⌄]
     北京 移动
```

三个必须注意的点：

1. **不能用 CallStyle**——一旦设了 `builder.style = CallStyle`，`setCustomHeadsUpContentView` 会被完全忽略。
2. **文字颜色要自己判断深浅色主题**。RemoteViews 不解析主题属性，写死黑色在深色模式下看不见：
   ```kotlin
   val night = (resources.configuration.uiMode and UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES
   views.setTextColor(R.id.call_custom_title, if (night) 0xFFFFFFFF.toInt() else 0xFF1F1F1F.toInt())
   ```
3. **`setOnlyAlertOnce(true)` 仍然必要**——标签是异步补的，更新时不能重弹。

### 标签怎么"事后"补上

```
响铃瞬间  →  横幅：13800138150 / 北京 移动      （号段库离线可得）
  ↓ 0.7~6s
联网查到  →  CallGuard notifyChange
          →  ContentObserver 收到
          →  作废本地缓存 → 重查 → notify() 更新横幅
最终显示：13800138150 / 美团外卖
```

`ensureBannerLabel()` 里做了两件事：查一次（覆盖"缓存里已有"），注册观察者（覆盖"稍后才到"）。收到通知时**必须先 `invalidate()` 本地缓存**，否则重查会命中 60 秒 TTL 的旧值——这个坑在 `InCallScreen` 那边踩过一次。

### 代价

- 不再被系统识别为"通话通知"（`CallStyle` 的特权），锁屏上的呈现取决于 ROM
- 折叠态 / heads-up 的自定义内容高度上限是 48dp（Android 2025 通知模板），所以 `notification_call_custom.xml` 固定 48dp；展开态改用 `notification_call_custom_large.xml`（52dp 按钮 + 6dp 上下 padding），否则折叠进通知栏时圆按钮会被系统上下裁掉

## 调试提示：来电横幅会盖住标签

排查这套东西时容易被这个坑住。来电瞬间 SystemUI 会弹 heads-up 通知，覆盖屏幕顶部约 430px：

```
来电横幅（"13800138150 · OpenDialer · 现在 / 拒接 / 接听"）  ← y ≈ 120-430
─────────────────────────────────────────
来电                                                        ← 被盖住一半
13800138150
北京 移动          ← 也被盖住，看起来"没显示"
```

截图里看着像"标签没渲染出来"、像是我改坏了什么，其实**一直渲染正常**，只是被横幅压在下面。

判断方法：`uiautomator dump` 看文本在不在渲染树、以及它的 bounds。

```bash
adb shell uiautomator dump /sdcard/ui.xml
adb shell cat /sdcard/ui.xml | grep 北京
```

或者直接接听（`input keyevent 5`），横幅消失后就看得见了。

## 那条 `<queries>` 是必须的

Android 11（API 30）起有包可见性限制。**光装了两个应用不够**——即使 CallGuard 的 `LabelProvider` 已经注册、`content call` 从 adb shell 也能通，本应用进程调用 `ContentResolver.call()` 依然会抛：

```
java.lang.IllegalArgumentException: Unknown authority com.callguard.app.provider
```

因为默认情况下，一个应用看不见别的包，自然也看不见它的 provider（shell 有全可见性，所以命令行测试会误导人）。

修法就是清单里声明我们要访问的那一个 authority。只声明这一个，不申请 `QUERY_ALL_PACKAGES`。

## 为什么放在 UI 层

数据层（`OngoingCall` / `CallManager`）是 Hilt 单例 + 状态机，把外部查询塞进去意味着引入新的依赖、新的生命周期管理。而我们只需要在"这一通电话、这个号码"上显示两行字。

放在 `InCallScreen` 里：

- `remember(uiState.callId, uiState.callerNumber)` — 换电话/换号码自动重查
- `LaunchedEffect` — 协程随 Composable 生命周期结束，不泄漏
- 查询失败一律 `null` — 界面退回系统默认显示，不会因为 CallGuard 没装而异常

代价是 UI 重建会重查一次（有 60 秒缓存兜底，实际上跨进程调用只在第一通电话发生一次）。

## 双卡：通话记录 / 详情 / 通话中的 SIM 标识

**数据来源**：`CallLog.Calls.PHONE_ACCOUNT_ID`。对电话账户来说这个 id 就是 `subscriptionId`，
拿它去 `SubscriptionManager.getActiveSubscriptionInfoList()` 里匹配即可。

| 位置 | 改动 |
|---|---|
| `data/calls/CallsData.kt` | projection 加 `PHONE_ACCOUNT_ID`，映射进实体 |
| `data/calls/CallDetailData.kt` | 同上（详情页每一通记录可能有不同的卡） |
| `DialerCallEntity` / `DialerCall` / `DetailCall` | 新增 `phoneAccountId: String?` |
| `core/common/telecom/SimInfo.kt` | `SimInfoResolver`：id → 卡槽 / 运营商名 / `iconTint` |
| `core/common/ui/SimBadge.kt` | "● SIM 2" 徽标 + `rememberSimInfos()` |
| `feature/calls/CallsScreen.kt` | 列表**标题行**右侧显示紧凑徽标（`● 2`，不挤占号码） |
| `feature/callDetail/CallDetailScreen.kt` | 每一通记录的时间旁显示 `SIM 2` |
| `feature/inCall/OngoingCall.kt` | 从 `Call.Details.accountHandle.id` 解析，写进 `OngoingCallState` |
| `feature/inCall/InCallDetails.kt` | 号码下方显示 `SIM 2 · 中国联通` |

**只在双卡时显示**：`rememberSimInfos()` 在单卡时返回空 map，`resolveForBadge()` 单卡返回 null——
单卡手机上每通电话挂个 "SIM 1" 纯属噪音。

颜色用系统给每张卡分配的 `SubscriptionInfo.iconTint`（圆点），文字保持 `onSurfaceVariant`，
避免深色主题下彩色文字看不清。运营商名优先用用户自定义的 `displayName`，其次 `carrierName`，
都没有就回落成 "SIM n"。

## 协议

见 `docs/label-protocol.md`（在 CallGuard 仓库里）。核心：

```
v1
region=北京 移动
line1=✅ 美团外卖
line2=⚠ 被 2 家标记 · 骚扰电话 ×15、推销 ×1
tone=warn
source=kit9
queried_at=1794746345110
lookup=1
```

- 第一行必须是 `v1`，不认识就整体丢弃
- 其余 `key=value`，顺序无关，未知 key 忽略
- `region` 是号段归属地，恒用中性色（只是地理信息，没有褒贬）
- `lineN` 从 1 连续，遇到空停止，按 `tone` 着色
- `tone` 只是建议，颜色由本应用按自己的 Material You 主题决定
- `source` / `queried_at` 显示成「免费接口 · 3 天前」
- `failed=1` → 「免费查询失败」，没有 `lineN`
- `lookup=1` → 显示「用付费接口重查」

`region` 和 `lineN` 分开是必须的，不只是好看：早先把归属地也塞进 `line1..N`，着色规则只能靠"是不是第一行"猜，结果**只有一行标签时（`⚠ 诈骗电话 ×47`）被当成归属地，红色丢掉**。

## 包名与应用名

自己的构建用 `com.callguard.dialer`，和 CallGuard（`com.callguard.app`）成一套。

```kotlin
// app/build.gradle.kts
applicationId = "com.callguard.dialer"
```

**`namespace` 保持 `dev.alenajam.opendialer` 不变**——改了要动几百个 import，没有收益。`applicationId` 才是安装后的包名。

连带改的还有几个硬编码了上游包名的字符串（不改也能跑，只是不统一）：

| 位置 | 内容 |
|---|---|
| `CallNotificationManager` | 4 个通知渠道 ID → `com.callguard.dialer.notification_channel.*` |
| `CallNotificationManager` | 2 个广播 action → `com.callguard.dialer.CALL_ACCEPT` / `CALL_DECLINE` |
| `AndroidManifest.xml` | 上面两个 action 的 receiver intent-filter |

应用名：`Phone` / 中文「拨号」。

> 给上游提 PR 时**不要带这些包名改动**——那是分叉的选择，不是上游要的。

## 与上游同步

因为改动集中在 `core:common` 的 1 个新文件 + `feature:inCall` 的 4 个文件 + 1 处清单，且都不碰公共 API，`git merge` 上游时冲突概率很低。真要冲突，最坏情况是重放这几个改动。

```bash
git remote add upstream https://github.com/oxcened/opendialer.git
git fetch upstream
git merge upstream/main
```

## 构建

需要 JDK 21；Android SDK 缺 `platforms;android-37` 时**不用手动装**——AGP 会自动下载（本机 `sdkmanager --list` 里根本没有这个包，但 AGP 的自动安装能拿到 `platforms/android-37.0`）。

**Gradle 自己的缓存和工具链全部放在项目目录里**，不写 C 盘（`~/.gradle` 已经被彻底删掉）：

```powershell
cd D:\code\opendialer
$env:GRADLE_USER_HOME = 'D:\code\opendialer\.gradle-home'   # 发行包 + 依赖缓存（~1.4 GB）
$env:TEMP = 'D:\code\opendialer\.tmp'
$env:TMP  = $env:TEMP
$env:JAVA_HOME = 'D:\code\opendialer\.tooling\jdk-25'       # Temurin 25，见下
$jdk21 = 'C:\Program Files\Wolfram Research\Wolfram\15.0\SystemFiles\Java\Windows-x86-64'

.\gradlew.bat :app:assembleDebug "-Dorg.gradle.java.installations.paths=$jdk21"
```

三个点：

- **`gradle/gradle-daemon-jvm.properties` 要求 JDK 25**（`toolchainVersion=25`）。没有的话 Gradle 会去 GitHub 下载 Temurin 25，
  而 **GitHub 在这台机器上超时**，构建会卡在 `Unable to download toolchain`。所以 JDK 25 放在 `.tooling/jdk-25`
  （从清华镜像下的 Temurin 25.0.5+8），用 `JAVA_HOME` 指给它，避免任何下载。
- 各模块声明的是 `jvmToolchain(21)`，所以还要让 Gradle 找到本机 JDK 21 —— 就是那句
  `-Dorg.gradle.java.installations.paths`。
- `.gradle-home/`、`.tooling/`、`.tmp/` 都已写进 `.gitignore`。

首次构建约 6 分钟（Gradle 9.7 + 全部依赖 + SDK 37 + 一个 JDK 25）。

> 实测网速：**单文件直连 20~27 MB/s**（Gradle 发行包 143 MB / 7 s，JDK 135 MB / 5 s），
> 但 Gradle 解析依赖时只有 **~3 MB/s**——几千个小文件（POM/JAR/metadata），瓶颈在请求数不在带宽。
