package com.qm.qqzygisk.hook.app.hooker

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.qm.qqzygisk.hook.app.chat.NtMsgAccess
import com.qm.qqzygisk.hook.app.chat.RecalledBadge
import com.qm.qqzygisk.hook.app.chat.RecalledMessageKey
import com.qm.qqzygisk.hook.app.chat.RecalledMessages
import com.qm.qqzygisk.hook.app.chat.RecalledRecordReader
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 在宿主 AIO 消息行的原消息气泡外圈绘制“已撤回”红色边框。
 *
 * 入口是 `AIOBubbleMsgItemVB.handleUIState(MviUIState)`：宿主每次把模型绑定到行、以及行
 * 滚进/滚出可见区都会经过它。before 先撤掉上一次的红框，after 重新读取当前模型再决定是否加框，
 * 保证行被复用到另一条消息时不会带着上一条消息的边框。只有真正的绑定状态
 * （`AIOMsgItemUIState$AIOMsgItemState`）才做 clear/rebind；ScrollIntoScreen / ScrollOutScreen
 * 等纯滚动状态不改 View，直接跳过，避免滚动时反复无效写入。
 *
 * 行身份由 [RecalledRecordReader] 从 `MsgRecord` 读取：`GrayTipsMsgItem`（含子类）以及元素列表
 * 为空、含灰条或未知元素类型的记录一律得到 null，renderer 收到 null 即清除该行旧边框。
 * 灰条不能靠“这一行有没有气泡”来排除：撤回提示本身也可能带可复用的气泡结构。
 * 撤回通知刷新时从“行 → 弱引用绑定器”现场重读当前记录，不沿用此前绑定的消息身份，
 * 因此行被复用成灰条后，迟到的通知同样只会清框，不会照着旧 key 误加边框。
 *
 * 所有宿主符号都按“契约形状”定位，不写死混淆名：
 *  - handleUIState：精确名 + 单参数为 `com.tencent.mvi.base.mvi.MviUIState`、返回 void；
 *  - 模型 getter：声明无参、返回 `com.tencent.mobileqq.aio.msg.AIOMsgItem` 且唯一；
 *  - 根 View getter：声明无参、返回 `android.view.View` 且唯一。
 *
 * 任一形状漂移只 warn 一次并跳过本 hook，异常不外抛，不影响其它模块 hook 的安装。
 */
internal object RecalledBadgeHooker {
    private const val VB_CLASS = "com.tencent.mobileqq.aio.msglist.holder.AIOBubbleMsgItemVB"
    private const val MSG_ITEM_CLASS = "com.tencent.mobileqq.aio.msg.AIOMsgItem"

    /** 独立撤回提示等灰条的消息项类型；模型 getter 仍按 `AIOMsgItem` 形状返回，这里是运行期显式拒绝。 */
    private const val GRAY_TIP_ITEM_CLASS = "com.tencent.mobileqq.aio.msg.GrayTipsMsgItem"
    private const val MVI_STATE_CLASS = "com.tencent.mvi.base.mvi.MviUIState"
    private const val BUBBLE_CLASS = "com.tencent.qqnt.aio.holder.template.BubbleLayoutCompatPress"
    private const val BUBBLE_SIMPLE_NAME = "BubbleLayoutCompatPress"

    /**
     * 真正的“把模型绑定到行”的状态。handleUIState 同时接收 ScrollIntoScreen / ScrollOutScreen
     * 等纯滚动状态不重建 View，无需反复清框和重新绑定。
     */
    private const val MSG_ITEM_STATE_CLASS =
        "com.tencent.mobileqq.aio.msglist.holder.AIOMsgItemUIState\$AIOMsgItemState"

    private const val RECORD_GETTER = "getMsgRecord"

    private val lock = Any()

    /** SharedPreferences 载入与唯一的刷新 listener 只做一次。 */
    private val storeStarted = AtomicBoolean(false)

    /** 形状漂移的告警只输出一次，避免每个 Activity 生命周期刷屏。 */
    private val driftLogged = AtomicBoolean(false)

    /** 运行期绑定异常同样只报一次，避免滚动时刷日志。 */
    private val runtimeFailureLogged = AtomicBoolean(false)

    /** 绑定状态类缺失（版本漂移）只提示一次，随后退化为对所有状态生效。 */
    private val bindStateMissingLogged = AtomicBoolean(false)

    /** 灰条消息项类缺失同样只提示一次；元素类型检查仍然生效。 */
    private val grayItemMissingLogged = AtomicBoolean(false)

    @Volatile
    private var installedLoader: ClassLoader? = null

    @Volatile
    private var msgGetter: Method? = null

    @Volatile
    private var rootGetter: Method? = null

    @Volatile
    private var recordGetter: Method? = null

    /** 记录身份读取器；形状缺失时不安装，避免用不完整的身份误加边框。 */
    @Volatile
    private var reader: RecalledRecordReader? = null

    /** `GrayTipsMsgItem` 类型，用于在读取记录前显式排除灰条行。 */
    @Volatile
    private var grayItemType: Class<*>? = null

    /** 只有该状态类实例才执行 clear/rebind；为 null 表示宿主没有该类，退化为全部状态。 */
    @Volatile
    private var bindStateType: Class<*>? = null

    /**
     * 行 → 当前绑定器（两者都弱引用）：宿主复用消息行后，刷新通知要按绑定器现场重读记录，
     * 而不是沿用 Renderer 里缓存的身份。只在主线程读写（bind 与 refresh 都在主线程）。
     */
    private val rowBinders = WeakHashMap<View, WeakReference<Any>>()

    /**
     * 初始化标记存储并安装 hook。
     *
     * 由 [com.qm.qqzygisk.hook.app.QQEntry] 在宿主首个 Activity、ClassLoader 对齐后调用。
     * 失败返回 false，允许后续 Activity 重试，且不影响其它 hook。
     */
    fun install(context: Context): Boolean {
        startStore(context)
        val targetLoader = appClassLoader
        if (installedLoader === targetLoader) return true
        synchronized(lock) {
            if (installedLoader === targetLoader) return true
            val ok = runCatching { installHook() }.getOrElse { error ->
                drift("安装已撤回边框 hook 失败", error)
                false
            }
            if (ok) installedLoader = targetLoader
            return ok
        }
    }

    /**
     * 载入模块专有标记并注册唯一的刷新 listener：撤回事件到达时立即刷新已绑定的可见行。
     */
    private fun startStore(context: Context) {
        if (!storeStarted.compareAndSet(false, true)) return
        runCatching {
            // 先挂 listener 再触发载入：首次载入完成的通知不会因为注册晚一步而丢掉。
            RecalledMessages.addListener {
                // 通知到达时现场读账号：切换账号后，旧账号的记录不能把红框刷到新账号的会话行上。
                val account = runCatching { NtMsgAccess.selfUin() }.getOrDefault("")
                RecalledBadge.refresh(
                    identityOf = { row -> binderOf(row)?.let { messageBinder -> keyOf(messageBinder, account) } },
                    recalled = { key ->
                        account.isNotEmpty() &&
                            key.accountUin == account &&
                            enabled() &&
                            RecalledMessages.contains(key)
                    },
                    bubbleOf = ::bubbleOf,
                )
            }
            RecalledMessages.initialize(context)
        }.onFailure { Log.warn("初始化已撤回标记存储失败", it) }
    }

    private fun installHook(): Boolean {
        val vbType = Class.forName(VB_CLASS, false, appClassLoader)
        val uiState = uiStateMethodOf(vbType) ?: return false
        val msg = msgGetterOf(vbType) ?: return false
        val root = rootGetterOf(vbType) ?: return false
        val record = recordGetterOf(msg.returnType) ?: return false
        val recordReader = RecalledRecordReader(record.returnType)
        recordReader.unavailable?.let {
            drift("$RECORD_GETTER 返回的记录缺少 $it")
            return false
        }
        msgGetter = msg
        rootGetter = root
        recordGetter = record
        reader = recordReader
        grayItemType = grayItemTypeOf()
        bindStateType = bindStateTypeOf()
        uiState.hook {
            before {
                runCatching {
                    // 只有真正的绑定状态才动边框；纯滚动状态保持现状，不做多余的 View 写入。
                    if (!isBindState(args.firstOrNull())) return@runCatching
                    // 先撤掉旧红框：这一行马上要显示新模型，边框不能跟着行复用到下一条消息。
                    val row = rootGetter?.invoke(instance) as? View ?: return@runCatching
                    RecalledBadge.clear(row)
                }.onFailure { reportFailure("清除已撤回边框失败", it) }
            }
            after {
                runCatching {
                    if (!isBindState(args.firstOrNull())) return@runCatching
                    bind(instance)
                }.onFailure { reportFailure("绑定已撤回边框失败", it) }
            }
        }
        Log.info("已撤回边框 hook 安装完成")
        return true
    }

    private fun bindStateTypeOf(): Class<*>? =
        runCatching { Class.forName(MSG_ITEM_STATE_CLASS, false, appClassLoader) }.getOrNull()
            ?: run {
                if (bindStateMissingLogged.compareAndSet(false, true)) {
                    Log.warn("未找到 $MSG_ITEM_STATE_CLASS，已撤回边框对所有 handleUIState 状态生效")
                }
                null
            }

    private fun isBindState(state: Any?): Boolean {
        if (state == null) return false
        val type = bindStateType ?: return true
        // 也按类名兜底：状态类若来自另一份 ClassLoader 拷贝，isInstance 会失配。
        return type.isInstance(state) || state.javaClass.name == MSG_ITEM_STATE_CLASS
    }

    /** after：重新读当前模型，先排除灰条/空记录，再按现场账号与会话身份判断这行要不要加框。 */
    private fun bind(binder: Any) {
        val row = rootGetter?.invoke(binder) as? View ?: return
        rememberBinder(row, binder)
        val bubble = bubbleOf(row)
        val account = NtMsgAccess.selfUin()
        val key = keyOf(binder, account)
        val recalled = key != null && enabled() && RecalledMessages.contains(key)
        // key 为 null 的灰条/系统行也调用渲染器，让它在行复用时清掉上一条消息留下的红框。
        RecalledBadge.bind(row, bubble, key, recalled)
    }

    /** 记录行当前对应的绑定器；宿主复用同一条目时绑定器通常不变，不重复分配弱引用。 */
    private fun rememberBinder(
        row: View,
        binder: Any,
    ) {
        if (binderOf(row) === binder) return
        rowBinders[row] = WeakReference(binder)
    }

    private fun binderOf(row: View): Any? = rowBinders[row]?.get()

    /**
     * [account] 由调用方现场读取一次后传入：bind 用当前账号，刷新用通知到达时捕获的账号，
     * 两条路径都不会在循环里重复查账号。
     */
    private fun keyOf(
        binder: Any,
        account: String,
    ): RecalledMessageKey? {
        if (account.isEmpty()) return null
        val message = msgGetter?.invoke(binder) ?: return null
        // 先按消息项类型拒绝灰条，避免记录身份字段与已撤回消息同 key 时被当成原消息。
        if (isGrayTipItem(message)) return null
        val record = recordGetter?.invoke(message) ?: return null
        return reader?.keyOf(record, account)
    }

    /**
     * 灰条消息项按类型拒绝，包含子类；类若来自另一份 ClassLoader，isInstance 会失配，
     * 因此再按继承链上的类名复核一次。
     */
    private fun isGrayTipItem(message: Any): Boolean {
        val declared = grayItemType
        if (declared != null && declared.isInstance(message)) return true
        var type: Class<*>? = message.javaClass
        while (type != null) {
            if (type.name == GRAY_TIP_ITEM_CLASS) return true
            type = type.superclass
        }
        return false
    }

    private fun enabled(): Boolean = HookSettings.isEnabled(AntiRevokeHooker.key, AntiRevokeHooker.defaultEnabled)

    private fun uiStateMethodOf(type: Class<*>): Method? {
        // 只接受已核实的三版一致签名：handleUIState(MviUIState):void，且必须唯一。
        val candidates =
            type.declaredMethods.filter {
                it.name == "handleUIState" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0].name == MVI_STATE_CLASS &&
                    it.returnType == Void.TYPE
            }
        if (candidates.size != 1) {
            drift("$VB_CLASS.handleUIState(MviUIState) 未找到唯一入口 (候选=${candidates.size})")
            return null
        }
        return candidates[0].apply { isAccessible = true }
    }

    private fun msgGetterOf(type: Class<*>): Method? {
        val messageType = runCatching { Class.forName(MSG_ITEM_CLASS, false, appClassLoader) }.getOrNull()
        if (messageType == null) {
            drift("$MSG_ITEM_CLASS 不存在")
            return null
        }
        return uniqueNoArg(type, "模型 getter", messageType)
    }

    private fun rootGetterOf(type: Class<*>): Method? = uniqueNoArg(type, "根 View getter", View::class.java)

    private fun uniqueNoArg(
        type: Class<*>,
        what: String,
        returnType: Class<*>,
    ): Method? {
        val candidates =
            type.declaredMethods.filter {
                it.parameterCount == 0 && !it.isBridge && it.returnType == returnType
            }
        if (candidates.size != 1) {
            drift("$VB_CLASS 的${what}必须唯一返回 ${returnType.name} (候选=${candidates.size})")
            return null
        }
        return candidates[0].apply { isAccessible = true }
    }

    private fun recordGetterOf(type: Class<*>): Method? {
        val method =
            runCatching { type.getMethod(RECORD_GETTER) }.getOrNull()
                ?.takeIf { it.parameterCount == 0 }
        if (method == null) {
            drift("${type.name}.$RECORD_GETTER() 未找到")
            return null
        }
        return method.apply { isAccessible = true }
    }

    private fun grayItemTypeOf(): Class<*>? =
        runCatching { Class.forName(GRAY_TIP_ITEM_CLASS, false, appClassLoader) }.getOrElse {
            if (grayItemMissingLogged.compareAndSet(false, true)) {
                Log.warn("未找到 $GRAY_TIP_ITEM_CLASS，灰条仅按消息元素类型排除")
            }
            null
        }

    /**
     * 气泡是 root 的直接子项（`BubbleLayoutCompatPress`，Vertical LinearLayout），也是红色边框的锚点。
     * 保留一层兜底深度，模板偶尔多包一层时边框仍然可用。
     * 这里只负责找锚点，不参与“是不是原消息”的判断：灰条结构上也可能带气泡。
     */
    private fun bubbleOf(root: View): View? {
        val group = root as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (isBubble(child)) return child
        }
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index) as? ViewGroup ?: continue
            for (inner in 0 until child.childCount) {
                val candidate = child.getChildAt(inner)
                if (isBubble(candidate)) return candidate
            }
        }
        return null
    }

    private fun isBubble(view: View): Boolean {
        var type: Class<*>? = view.javaClass
        while (type != null) {
            if (type.name == BUBBLE_CLASS || type.simpleName == BUBBLE_SIMPLE_NAME) return true
            type = type.superclass
        }
        return false
    }

    /** 运行期异常只报第一次，滚动/复用时的重复异常不刷日志。 */
    private fun reportFailure(
        message: String,
        error: Throwable?,
    ) {
        if (runtimeFailureLogged.compareAndSet(false, true)) {
            Log.warn(message, error)
        }
    }

    private fun drift(
        message: String,
        error: Throwable? = null,
    ) {
        if (driftLogged.compareAndSet(false, true)) {
            Log.warn("已撤回边框 hook 跳过: $message", error)
        }
    }
}
