package com.wecare.tvns.sdkdemo

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.wecare.tvns.sdkdemo.databinding.ActivityMainBinding
import com.wecare.tvns.sdk.TvnsPermissions
import com.wecare.tvns.sdk.TvnsSdk
import com.wecare.tvns.sdk.TvnsSdkListener
import com.wecare.tvns.sdk.TvnsScanListener
import com.wecare.tvns.sdk.model.ChargeStatus
import com.wecare.tvns.sdk.model.CustomParameters
import com.wecare.tvns.sdk.model.FinishReason
import com.wecare.tvns.sdk.model.PatternMode
import com.wecare.tvns.sdk.model.StimulateQueryResult
import com.wecare.tvns.sdk.model.StimulateStartArgs
import com.wecare.tvns.sdk.model.TvnsCallback
import com.wecare.tvns.sdk.model.TvnsConfig
import com.wecare.tvns.sdk.model.TvnsDeviceInfo
import com.wecare.tvns.sdk.model.TvnsDeviceState
import com.wecare.tvns.sdk.model.TvnsError
import com.wecare.tvns.sdk.model.TvnsScanResult
import com.wecare.tvns.sdk.model.UseHistory
import com.wecare.tvns.sdk.model.WearStatus
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * SDK 的手动联调台。
 *
 * 日志红线：这里只写状态、设备属性和错误码。**不要把收到的字节、十六进制串或逐字段的
 * 解码结果打到日志里** —— 报文结构是这套 SDK 存在的理由，日志里出现等于白做。
 */
class MainActivity : AppCompatActivity(), TvnsSdkListener, TvnsScanListener {

    private lateinit var ui: ActivityMainBinding

    private val adapter = DeviceAdapter { onDevicePicked(it) }

    /** 只留最近若干行，跑久了不至于把整屏日志拖成几万行字符串。 */
    private val logLines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** 使用记录里的开始时间要带日期：它和日志行首那个「收到的时刻」可能差好几天。 */
    private val historyClock = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    /** 界面存活期间拿到的门面实例；onStop 时置空，避免回调打到已经拆掉的界面上。 */
    private var sdk: TvnsSdk? = null

    private var connected = false

    /**
     * 设备那边是否正在刺激。
     *
     * 只有它为 true 时，设备报的「未运行」才当成耳机上的结束键 —— 连接之后 SDK 自己补的
     * 那次查询报的也是「未运行」，那一下什么都不代表。
     */
    private var stimulating = false

    /**
     * 刚下发成功、设备还没确认在跑。这段时间里它报「未运行」不算数。
     *
     * 对齐 Flutter 蓝本 startStimulate 之后那次读回的判断（「设备刚启动完还没把状态记下来，
     * 先留着本地状态」）；没有这一道，开始刺激刚回来就会被自己的上报打回去。
     */
    private var awaitingDeviceRun = false

    /** 已经因为设备报「未运行」走过一次跟随停止，避免同一条上报反复触发。 */
    private var stopping = false

    /**
     * 刺激是否被暂停。设备说了算。
     *
     * 耳机上的暂停/恢复键会让设备主动上报一次 AT_INFO，SDK 收到后立刻补一次状态查询，
     * 结果从 [onQueryResult] 回来写这里；本地下发成功时也先记一笔，好让按钮文字立刻跟上，
     * 随后设备报什么就是什么。
     */
    private var paused = false

    /** 上一帧刺激摘要。设备连着报几次一样的状态，没变就不重复进日志。 */
    private var lastStimulateSummary: String? = null

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val denied = result.filterValues { granted -> !granted }.keys
        if (denied.isEmpty()) {
            appendLog("权限已授予")
            startScan()
        } else {
            appendLog("权限被拒：" + denied.joinToString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ViewCompat.setOnApplyWindowInsetsListener(ui.main) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        ui.listDevices.layoutManager = LinearLayoutManager(this)
        ui.listDevices.adapter = adapter
        ui.btnStopScan.isEnabled = false
        ui.tvStrength.text = getString(R.string.label_strength, ui.seekStrength.progress)
        ui.tvStimulate.text = getString(R.string.label_stimulate, getString(R.string.state_none))
        renderConnection(null)
        renderDeviceInfo(null)
        bindButtons()
        renderModeFields()
    }

    override fun onStart() {
        super.onStart()
        val instance = TvnsSdk.getInstance()
        instance.init(applicationContext, TvnsConfig.defaults())
        instance.setLicense(kDemoLicense, null)
        instance.setSdkListener(this)
        instance.setScanListener(this)
        sdk = instance
        appendLog("SDK 版本 " + TvnsSdk.getVersion())
        // 连接握在 SDK 手里，界面重建（转屏）不会掉线。属性快照还在就先铺上。
        val info = instance.deviceInfo
        if (info != null) {
            renderDeviceInfo(info)
        }
    }

    override fun onStop() {
        super.onStop()
        // 先把监听摘掉再撒手：否则回调里碰到的都是已经 detach 的 View。
        sdk?.let { instance ->
            instance.stopScan()
            instance.setSdkListener(null)
            instance.setScanListener(null)
        }
        sdk = null
        // 收尾标记不跨 onStart/onStop：回调可能停在路上，留着会让重进页面后不再跟随设备。
        awaitingDeviceRun = false
        stopping = false
    }

    // ---------- 按钮 ----------

    private fun bindButtons() {
        ui.btnScan.setOnClickListener { requestScan() }
        ui.btnStopScan.setOnClickListener { sdk?.stopScan() }
        ui.btnDisconnect.setOnClickListener {
            adapter.setConnectedMac(null)
            sdk?.disconnect()
            appendLog("已请求断开")
        }
        ui.btnStart.setOnClickListener { startStimulate() }
        ui.btnPauseResume.setOnClickListener { togglePauseResume() }
        ui.btnStopStimulate.setOnClickListener {
            // 停了就没有「暂停中」这回事了，标签要跟着回到「暂停」，本地的「在跑」也一起收掉。
            simpleCommand("停止", afterSuccess = { setLocalStimulate(false, false) }) {
                sdk?.stopStimulate(it)
            }
        }

        ui.btnStrengthDown.setOnClickListener { nudgeStrength(-1) }
        ui.btnStrengthUp.setOnClickListener { nudgeStrength(1) }
        ui.seekStrength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                ui.tvStrength.text = getString(R.string.label_strength, progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            // 拖动过程中每挪一格都发命令只会连着吃 Busy，落手时发一次就够。
            override fun onStopTrackingTouch(bar: SeekBar) {
                // 滑条在 API 26 以下没有 min，能一路拖到 0；0 是设备不认的档位，先拉回下限。
                val target = bar.progress.coerceAtLeast(TvnsSdk.kMinStrength)
                if (target != bar.progress) {
                    bar.progress = target
                }
                adjustStrength(target)
            }
        })

        ui.btnCopyLog.setOnClickListener { copyLog() }
        // 模式选择保留 —— 这一轮就是要分别试 A 和 B。
        ui.groupMode.setOnCheckedChangeListener { _, _ -> renderModeFields() }
    }

    // ---------- 命令下发 ----------
    //
    // 没有「上一条还没回来就不许再发」这道门禁。设备一次只受理一条命令是设备自己的事；
    // 对齐 Flutter 蓝本：SDK 从不因「忙」回绝，界面也不该自己加一道 —— 那条门禁正是
    // 「按了没反应」的来源。「命令在途」只在设备信息那行里显示，不拦人。

    /**
     * 记下本地这一轮的状态并刷新按钮文字。随后设备报什么就是什么。
     *
     * [running] 为 true 时同时把 [awaitingDeviceRun] 立起来：命令成功只说明设备收下了，
     * 不代表它已经把状态记下来了，这段时间里它报「未运行」不算数。
     */
    private fun setLocalStimulate(running: Boolean, pausedNow: Boolean) {
        stimulating = running
        paused = pausedNow
        awaitingDeviceRun = running
        updateCommandButtons()
    }

    /** 只翻暂停标签。暂停状态设备随后会重新报一遍，以那一遍为准。 */
    private fun setPaused(value: Boolean) {
        paused = value
        updateCommandButtons()
    }

    /** 暂停/恢复共用一个按钮：按当前状态决定这一下是哪个动作。 */
    private fun togglePauseResume() {
        if (paused) {
            simpleCommand("恢复", afterSuccess = { setPaused(false) }) { sdk?.continueStimulate(it) }
        } else {
            simpleCommand("暂停", afterSuccess = { setPaused(true) }) { sdk?.waitStimulate(it) }
        }
    }

    private fun updateCommandButtons() {
        ui.btnStart.isEnabled = connected
        ui.btnStopStimulate.isEnabled = connected
        ui.btnPauseResume.isEnabled = connected
        ui.btnStrengthDown.isEnabled = connected
        ui.btnStrengthUp.isEnabled = connected
        ui.seekStrength.isEnabled = connected
        // 标签写的是「下一次点击会做什么」：暂停中就该显示「恢复」。
        ui.btnPauseResume.setText(if (paused) R.string.btn_resume else R.string.btn_pause)
    }

    /**
     * 只回成功/失败、不带结果值的命令。
     *
     * [afterSuccess] 放在 [send] 前面，为的是让尾随 lambda 仍然落在 [send] 上；
     * 只有设备真的照做了才调，用来跟着翻那些「本地记着」的状态。
     */
    private fun simpleCommand(
        what: String,
        afterSuccess: () -> Unit = {},
        send: (TvnsCallback<Boolean>) -> Unit,
    ) {
        send(object : TvnsCallback<Boolean> {
            override fun onSuccess(result: Boolean?) {
                appendLog(what + " 成功")
                afterSuccess()
                refreshState()
            }

            override fun onError(error: TvnsError) {
                appendLog(what + " 失败 " + error.code + "：" + error.message)
            }
        })
    }

    /**
     * 命令成功后主动问一次设备状态。
     *
     * 界面上显示的以设备为准 —— 刚下发的那一条到底生效没有，只有设备能说。
     * 这不是轮询：每次用户动作只补这一枪，定时器一个都没有。
     *
     * 渲染不在这里做：查询带回来的状态同时会走 [onQueryResult]，那条通道也承载设备自己触发
     * 的查询，只留一个渲染入口。
     */
    private fun refreshState() {
        sdk?.queryState(object : TvnsCallback<StimulateQueryResult> {
            override fun onSuccess(result: StimulateQueryResult?) = Unit

            override fun onError(error: TvnsError) {
                appendLog("查询失败 " + error.code + "：" + error.message)
            }
        })
    }

    // ---------- 强度调整 ----------

    /**
     * 加/减一档。
     *
     * 基于滑条当前值算目标值：设备侧的档位变化会经 [onQueryResult] 写回滑条，所以这里的
     * 起点始终是设备此刻的真实档位，而不是本地一厢情愿的计数。
     */
    private fun nudgeStrength(delta: Int) {
        val current = ui.seekStrength.progress
        val target = current + delta
        if (target < TvnsSdk.kMinStrength || target > TvnsSdk.kMaxStrength) {
            appendLog(getString(R.string.strength_limit, current))
            return
        }
        adjustStrength(target)
    }

    private fun adjustStrength(target: Int) {
        simpleCommand(
            what = "调整强度到 " + target,
            afterSuccess = { ui.seekStrength.progress = target },
        ) { sdk?.adjustStimulateStrength(target, it) }
    }

    private fun requestScan() {
        if (sdk == null) {
            appendLog("SDK 尚未初始化")
            return
        }
        if (TvnsPermissions.hasAllGranted(this)) {
            startScan()
        } else {
            appendLog("申请权限：" + TvnsPermissions.required().joinToString())
            permissions.launch(TvnsPermissions.required())
        }
    }

    private fun startScan() {
        adapter.clear()
        sdk?.startScan()
    }

    private fun onDevicePicked(result: TvnsScanResult) {
        appendLog("连接 " + result.name)
        adapter.setConnectedMac(result.mac)
        sdk?.connect(result.mac)
    }

    private fun startStimulate() {
        val instance = sdk ?: return
        val mode = selectedMode()
        val args: StimulateStartArgs
        val summary: String
        if (mode == PatternMode.CUSTOM) {
            val duration = ui.editDuration.text.toString().toIntOrNull()
            if (duration == null) {
                appendLog("自定义模式要填正整数时长")
                return
            }
            if (duration < StimulateStartArgs.kCustomDurationMinSeconds ||
                duration > StimulateStartArgs.kCustomDurationMaxSeconds
            ) {
                appendLog(
                    getString(
                        R.string.duration_range,
                        StimulateStartArgs.kCustomDurationMinSeconds / 60,
                        StimulateStartArgs.kCustomDurationMaxSeconds / 60
                    )
                )
                return
            }
            val custom = readCustomParameters() ?: return
            args = StimulateStartArgs.ofCustom(duration, custom)
            summary = modeName(mode) + " " + duration + " 秒"
        } else {
            // A/B 的时长由设备治疗方案决定，调用方无从指定。
            args = StimulateStartArgs.ofMode(mode)
            summary = modeName(mode) + " " +
                getString(R.string.duration_fixed, StimulateStartArgs.kModeDurationSeconds / 60)
        }
        appendLog("开始刺激：" + summary)
        instance.startStimulate(args, object : TvnsCallback<Boolean> {
            override fun onSuccess(result: Boolean?) {
                appendLog("开始刺激 成功")
                // 新起的一轮刺激不可能是暂停态。
                setLocalStimulate(running = true, pausedNow = false)
                // 设备刚收到命令未必已经把状态记下来，隔一次上报再问，别拿旧的把新的盖掉。
                refreshState()
            }

            override fun onError(error: TvnsError) {
                appendLog("开始刺激 失败 " + error.code + "：" + error.message)
            }
        })
    }

    private fun selectedMode(): PatternMode = when (ui.groupMode.checkedRadioButtonId) {
        R.id.radioB -> PatternMode.B
        R.id.radioCustom -> PatternMode.CUSTOM
        else -> PatternMode.A
    }

    /**
     * 把四个脉冲输入框读成 [CustomParameters]，读不出来就记一条日志并返回 null。
     *
     * 区间一律用 [CustomParameters] 上的常量，不写死数值。脉宽例外：它只有五个固定档位，
     * 不是区间，所以单独走 [readPulseWidth]。
     */
    private fun readCustomParameters(): CustomParameters? {
        val width = readPulseWidth(ui.editPulseWidth) ?: return null
        val frequency = readPulseValue(
            ui.editPulseFrequency, R.string.label_pulse_frequency,
            CustomParameters.kPulseFrequencyMin, CustomParameters.kPulseFrequencyMax
        ) ?: return null
        val start = readPulseValue(
            ui.editPulseStart, R.string.label_pulse_start,
            CustomParameters.kPulseStartDurationMin, CustomParameters.kPulseStartDurationMax
        ) ?: return null
        val end = readPulseValue(
            ui.editPulseEnd, R.string.label_pulse_end,
            CustomParameters.kPulseEndDurationMin, CustomParameters.kPulseEndDurationMax
        ) ?: return null
        return CustomParameters(width, frequency, start, end)
    }

    /**
     * 脉宽只有五个固定档位（[CustomParameters.kPulseWidths]），不是连续区间，所以不走
     * [readPulseValue] 的 min/max。不在这里挡，「110」这种落在 100~300 里但设备不认的值，
     * 会一路走到 [StimulateStartArgs.ofCustom] 才抛异常，崩在 UI 线程上。
     */
    private fun readPulseWidth(field: EditText): Int? {
        val value = field.text.toString().toIntOrNull()
        if (value == null) {
            appendLog(getString(R.string.pulse_param_invalid))
            return null
        }
        if (!CustomParameters.isSupportedPulseWidth(value)) {
            appendLog(
                getString(
                    R.string.pulse_width_unsupported,
                    CustomParameters.kPulseWidths.joinToString("/")
                )
            )
            return null
        }
        return value
    }

    private fun readPulseValue(field: EditText, labelRes: Int, min: Int, max: Int): Int? {
        val value = field.text.toString().toIntOrNull()
        if (value == null) {
            appendLog(getString(R.string.pulse_param_invalid))
            return null
        }
        if (value < min || value > max) {
            appendLog(getString(R.string.pulse_param_range, getString(labelRes), min, max))
            return null
        }
        return value
    }

    /**
     * 时长与四个脉冲输入框都只在自定义模式下可用。
     *
     * A/B 的时长和脉冲方案都由设备治疗方案决定，SDK 那边压根没有收这些值的口子 —— 让输入框
     * 留着可编辑，只会让人以为改得动。此处置灰，时长框另挂「固定 30 分钟」的提示，和 SDK 的
     * 类型约束对齐。
     */
    private fun renderModeFields() {
        val custom = selectedMode() == PatternMode.CUSTOM
        ui.editDuration.isEnabled = custom
        pulseFields().forEach { it.isEnabled = custom }
        if (custom) {
            ui.editDuration.hint = getString(R.string.hint_duration)
            if (ui.editDuration.text.isNullOrEmpty()) {
                ui.editDuration.setText(kDefaultDurationSeconds)
            }
            fillPulseDefaults()
        } else {
            ui.editDuration.setText("")
            ui.editDuration.hint = getString(
                R.string.duration_fixed, StimulateStartArgs.kModeDurationSeconds / 60
            )
        }
    }

    /** 四个脉冲输入框按 CustomParameters 构造参数的顺序排：脉宽、频率、起始、结束。 */
    private fun pulseFields(): List<EditText> = listOf(
        ui.editPulseWidth, ui.editPulseFrequency, ui.editPulseStart, ui.editPulseEnd
    )

    private fun fillPulseDefaults() {
        pulseFields().forEachIndexed { index, field ->
            if (field.text.isNullOrEmpty()) {
                field.setText(kDefaultPulseValues[index])
            }
        }
    }

    // ---------- TvnsSdkListener ----------

    override fun onStateChanged(state: TvnsDeviceState) {
        renderConnection(state)
        adapter.setConnectedMac(if (state.isConnected) state.mac else null)
        when {
            state.isConnected -> appendLog("已连接 " + state.mac)
            state.isEverConnected -> appendLog("连接断开 " + state.mac)
            else -> appendLog("连接失败 " + state.mac)
        }
    }

    override fun onDeviceInfoChanged(info: TvnsDeviceInfo) {
        renderDeviceInfo(info)
    }

    /**
     * 设备当前的刺激状态。页面「跟着耳机走」就是从这里走的。
     *
     * 耳机上按暂停/恢复/结束，设备都会主动推一次 AT_INFO；SDK 收到 AT_INFO 后立刻补一次状态
     * 查询，结果就走到这里。所以这条链路上不需要任何轮询 —— Flutter 蓝本也是这么连的。
     */
    override fun onQueryResult(result: StimulateQueryResult) {
        if (result.isWorking) {
            stimulating = true
            awaitingDeviceRun = false
        } else if (stimulating && !awaitingDeviceRun) {
            // 设备自己停了：耳机上的结束键，或者 A/B 治疗方案跑完了。
            stimulating = false
            onDeviceStopped()
        }
        // 没在跑就谈不上「暂停中」；设备报缺字段时也一并挡掉。
        paused = result.isWorking && result.isSuspended
        renderQuery(result)
        updateCommandButtons()
    }

    /**
     * 设备自己把刺激停了，本机跟着收尾。
     *
     * 对齐 Flutter 蓝本：那边一看见设备报 stopped、自己又没在收尾，就立刻补一条停止，
     * 好把这一轮在设备侧也收干净。[stopping] 挡住同一条上报反复触发。
     */
    private fun onDeviceStopped() {
        val instance = sdk ?: return
        if (stopping) {
            return
        }
        stopping = true
        appendLog("设备已结束刺激，跟随停止")
        instance.stopStimulate(object : TvnsCallback<Boolean> {
            override fun onSuccess(result: Boolean?) {
                stopping = false
                appendLog("跟随停止 成功")
            }

            override fun onError(error: TvnsError) {
                stopping = false
                appendLog("跟随停止 失败 " + error.code + "：" + error.message)
            }
        })
    }

    override fun onError(error: TvnsError) {
        appendLog("错误 " + error.code + "：" + error.message)
    }

    override fun onUseHistory(history: List<UseHistory>) {
        if (history.isEmpty()) {
            appendLog(getString(R.string.history_empty))
            return
        }
        // 设备攒下来的记录一次全给，逐条写一行，省得挤成一坨看不出哪段是哪段。
        history.forEach { appendLog(historyLine(it)) }
    }

    // ---------- TvnsScanListener ----------

    override fun onDeviceFound(result: TvnsScanResult) {
        adapter.upsert(result)
    }

    override fun onScanStateChanged(scanning: Boolean) {
        ui.btnScan.isEnabled = !scanning
        ui.btnStopScan.isEnabled = scanning
        appendLog(if (scanning) "扫描中" else "扫描已停")
    }

    override fun onScanFailed(error: TvnsError) {
        appendLog("扫描失败 " + error.code + "：" + error.message)
    }

    // ---------- 显示 ----------

    private fun renderConnection(state: TvnsDeviceState?) {
        val nowConnected = state != null && state.isConnected
        if (connected && !nowConnected) {
            // 断了这一轮就结束了。留着「设备在跑」，下次连上时那条初始化上报的「未运行」
            // 会被当成耳机上的结束键，凭空补一条停止出去。
            stimulating = false
            paused = false
            awaitingDeviceRun = false
        }
        connected = nowConnected
        val text = if (nowConnected && state != null) {
            getString(R.string.state_connected) + " " + state.mac
        } else {
            getString(R.string.state_disconnected)
        }
        ui.tvConnection.text = getString(R.string.label_connection, text)
        updateCommandButtons()
    }

    private fun renderDeviceInfo(info: TvnsDeviceInfo?) {
        if (info == null) {
            ui.tvDeviceInfo.text =
                getString(R.string.label_device_info, getString(R.string.state_none))
            return
        }
        val parts = ArrayList<String>(7)
        parts.add(getString(R.string.info_battery, info.battery))
//        parts.add(chargeName(info.chargeStatus))
//        parts.add(wearName(info.wearStatus))
        parts.add(modeName(info.defaultMode))
//        parts.add(getString(R.string.info_free_space, info.freeSpace))
        parts.add(info.firmwareVersion ?: getString(R.string.state_unknown))
        // 「命令在途」正是 Busy 的来源，联调时看得见才好判断为什么下一条命令被回绝。
        if (info.isSendingOperation) {
            parts.add(getString(R.string.info_sending))
        }
        ui.tvDeviceInfo.text = getString(R.string.label_device_info, parts.joinToString(" · "))
    }

    /**
     * 把设备报的刺激状态铺到界面上。
     *
     * 档位跟设备走，不跟本地走：在耳机上加档、设备自己换档，滑条都得跟着挪，
     * 否则用户在界面上看到的档位和他耳朵里感受到的对不上。
     */
    private fun renderQuery(result: StimulateQueryResult) {
        val parts = ArrayList<String>(6)
        parts.add(getString(if (result.isWorking) R.string.stim_running else R.string.stim_idle))
        if (result.isSuspended) {
            parts.add(getString(R.string.stim_suspended))
        }
        result.mode?.let { parts.add(modeName(it)) }
        result.stimulusValue?.let { parts.add(getString(R.string.label_strength, it)) }
        result.workingTimeSeconds?.let { parts.add(getString(R.string.stim_working_time, it)) }
        result.durationSeconds?.let { parts.add(getString(R.string.stim_duration, it)) }
        // 只有自定义模式才有回读的脉冲参数：A/B 是固定协议，设备本来就不带这组字段
        // （Flutter 蓝本也只在 MT_CUSTOM 时取）。设备没带时写明「未上报」，好和全 0 区分开。
        if (result.mode == PatternMode.CUSTOM) {
            val pulse = result.customParameters
            parts.add(
                if (pulse == null) {
                    getString(R.string.stim_pulse_absent)
                } else {
                    getString(
                        R.string.stim_pulse_params, pulse.pulseWidth, pulse.pulseFrequency,
                        pulse.pulseStartDuration, pulse.pulseEndDuration
                    )
                }
            )
        }

        // 代码改 progress 不会走 onStopTrackingTouch，所以这里不会反过来又发一条命令。
        result.stimulusValue?.let { ui.seekStrength.progress = it.coerceIn(0, ui.seekStrength.max) }

        val summary = parts.joinToString(" ")
        ui.tvStimulate.text = getString(R.string.label_stimulate, summary)
        // 设备可能连着报几次一样的状态，没变就不往日志里灌第二遍。
        if (summary != lastStimulateSummary) {
            lastStimulateSummary = summary
            appendLog("设备状态：" + summary)
        }
    }

    private fun modeName(mode: PatternMode?): String = when (mode) {
        PatternMode.A -> getString(R.string.mode_a)
        PatternMode.B -> getString(R.string.mode_b)
        PatternMode.CUSTOM -> getString(R.string.mode_custom)
        else -> getString(R.string.state_unknown)
    }

    /**
     * 一条使用记录铺成日志里的一行。
     *
     * 开始时间取的是记录自己的时间，不是收到它的时间 —— 设备离线期间攒下的记录会在重连后
     * 一起补报，两者能差出很久。
     */
    private fun historyLine(item: UseHistory): String = getString(
        R.string.history_record,
        historyClock.format(Date(item.startTimeSeconds * 1000L)),
        finishReasonName(item.finishReason),
        modeName(item.mode),
        item.durationSeconds
    )

    private fun finishReasonName(reason: FinishReason): String = when (reason) {
        FinishReason.NORMAL -> getString(R.string.finish_normal)
        FinishReason.OWN -> getString(R.string.finish_own)
        FinishReason.NEW_STIMULATE -> getString(R.string.finish_new_stimulate)
        FinishReason.LOW_BATTERY -> getString(R.string.finish_low_battery)
         FinishReason.OTHER -> getString(R.string.finish_other)
        else -> getString(R.string.finish_unknown)
    }

    private fun chargeName(status: ChargeStatus): String = when (status) {
        ChargeStatus.CHARGING -> getString(R.string.info_charge_charging)
        ChargeStatus.IDLE -> getString(R.string.info_charge_idle)
        else -> getString(R.string.state_unknown)
    }

    private fun wearName(status: WearStatus): String = when (status) {
        WearStatus.WEARING -> getString(R.string.info_wear_wearing)
        WearStatus.IDLE -> getString(R.string.info_wear_idle)
        else -> getString(R.string.state_unknown)
    }

    // ---------- 日志 ----------

    /** 复制的是日志区里看得见的那一份，行数上限就是 [kMaxLogLines]。 */
    private fun copyLog() {
        if (logLines.isEmpty()) {
            appendLog(getString(R.string.log_empty))
            return
        }
        val text = logLines.joinToString("\n")
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(kLogClipLabel, text))
        appendLog(getString(R.string.log_copied, logLines.size))
    }

    private fun appendLog(line: String) {
        logLines.addLast(clock.format(Date()) + "  " + line)
        while (logLines.size > kMaxLogLines) {
            logLines.removeFirst()
        }
        ui.tvLog.text = logLines.joinToString("\n")
        ui.scrollLog.post { ui.scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    private companion object {
        const val kMaxLogLines = 200
        const val kDefaultDurationSeconds = "60"
        const val kDemoLicense = "demo"

        /** 剪贴板里那条 ClipData 的标签，只在系统「刚刚复制」的浮层里露一下。 */
        const val kLogClipLabel = "tvns-log"

        /** 自定义模式下四个脉冲输入框的预填值：脉宽 us、频率 Hz、起始秒、结束秒。 */
        val kDefaultPulseValues = listOf("200", "20", "5", "5")
    }
}
