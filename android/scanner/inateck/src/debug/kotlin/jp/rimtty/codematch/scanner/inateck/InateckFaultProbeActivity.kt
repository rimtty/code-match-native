package jp.rimtty.codematch.scanner.inateck

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import jp.rimtty.codematch.scanner.api.ScannerDevice
import jp.rimtty.codematch.scanner.ble.*

/** Registered only by the opt-in fault APK; normal app manifests never expose it. */
class InateckFaultProbeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private lateinit var choices: LinearLayout
    private lateinit var report: TextView
    private var gateway: InateckReadOnlyFaultGateway? = null
    private var transport: InateckSdkTransport? = null
    private var session: BleSymbologySession? = null
    private var store: InMemorySymbologySnapshotStore? = null
    private var selected: ScannerDevice? = null
    private var restoreMode = false
    private var preparing = false
    private var sdkReadSucceeded = false
    private var physicalClosed = false
    private var releasedLate = false
    private var elapsedStart = 0L
    private var phase = "未実施"
    private var stopped = true
    private var closing = false
    private var runGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 48) }
        setContentView(ScrollView(this).apply { addView(body) })
        label("実SDK異常系・独立検証")
        label("通常アプリとSDK Probeを切断してから開始。設定の書き込みは遮断。SDK接続処理は実行します。コードを読まないでください。")
        button("読出し6秒タイムアウト検証：探索") { discover(false) }
        button("復元失敗検証：探索") { discover(true) }
        choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(choices)
        button("保留した応答を期限後に配信") {
            val s = session
            if (s?.state == BleSymbologySessionState.AwaitingTransportReset) {
                sdkReadSucceeded = gateway?.completedReadSucceeded == true
                releasedLate = gateway?.releaseCompletedRead() == true
            }
            render()
        }
        button("検証を終了・切断") { phase = "切断完了待ち"; stop(); render() }
        report = label("未実施")
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text; textSize = 18f; setPadding(0, 18, 0, 18); body.addView(this)
    }
    private fun button(text: String, action: () -> Unit) {
        body.addView(Button(this).apply { this.text = text; setOnClickListener { action() } })
    }

    private fun discover(restore: Boolean) {
        if (!stopped) return
        if (listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                .any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 1)
            phase = "権限を許可して再度探索してください"; render(); return
        }
        restoreMode = restore; stopped = false; preparing = false
        physicalClosed = false; releasedLate = false; sdkReadSucceeded = false
        session = null; selected = null
        store = InMemorySymbologySnapshotStore(INATECK_ANDROID_SDK_PROFILE_IDENTITY)
        val g = InateckReadOnlyFaultGateway(AndroidInateckSdkGateway(applicationContext, handler))
        gateway = g
        val t = InateckSdkTransport(g, nowMillis = SystemClock::elapsedRealtime)
        transport = t
        val token = ++runGeneration
        t.listener = BleTransportListener { event ->
            if (!stopped && token == runGeneration) when (event) {
                is BleTransportEvent.Connected -> connected(event.device)
                is BleTransportEvent.Disconnected -> {
                    physicalClosed = true
                    if (closing) finishStop()
                    render()
                }
                is BleTransportEvent.ConnectionFailed -> { phase = "SDK接続失敗"; render() }
                is BleTransportEvent.DisconnectFailed -> { phase = "切断確認失敗：新規接続禁止"; render() }
                else -> Unit
            }
        }
        val found = linkedMapOf<String, InateckSdkDevice>()
        choices.removeAllViews()
        phase = "実SDK探索中（5秒）"; render()
        if (!g.startDiscovery({ found[it.id] = it }, {})) { stop(); phase = "探索拒否"; render(); return }
        handler.postDelayed({
            if (!stopped && token == runGeneration) {
                g.stopDiscovery(); phase = "SDK端末を選択（${found.size}件）"; render()
                found.values.forEachIndexed { index, device ->
                    choices.addView(Button(this).apply {
                        text = "SDK端末 ${index + 1}：${device.name}"
                        setOnClickListener {
                            if (selected == null) {
                                val scanner = ScannerDevice(device.id, device.name)
                                selected = scanner; choices.removeAllViews()
                                elapsedStart = SystemClock.elapsedRealtime()
                                phase = "実SDK接続中"; render()
                                if (!t.connect(scanner)) { phase = "接続要求拒否"; render() }
                                this@InateckFaultProbeActivity.handler.post(ticker)
                            }
                        }
                    })
                }
            }
        }, 5_000)
    }

    private fun connected(device: ScannerDevice) {
        phase = "接続成功・実SDK設定読出し"
        if (restoreMode) {
            preparing = true
            if (transport?.read(INATECK_SETTINGS_ENDPOINT) { result ->
                preparing = false
                val snapshot = result.getOrNull()?.let {
                    InateckAreaNameSymbologyCodec.decodeSnapshot(device.id, it, SystemClock.elapsedRealtime())
                }
                if (snapshot == null) { phase = "基準取得失敗：復元試験未実施"; render() }
                else { store?.save(snapshot); beginSessionOwner(device) }
            } != true) { preparing = false; phase = "基準読出し拒否" }
        } else beginSessionOwner(device)
        render()
    }

    private fun beginSessionOwner(device: ScannerDevice) {
        session = BleSymbologySession(
            device, requireNotNull(transport),
            BleSymbologyProfile(INATECK_SETTINGS_ENDPOINT, InateckAreaNameSymbologyCodec, INATECK_ANDROID_SDK_PROFILE_IDENTITY),
            requireNotNull(store), nowMillis = SystemClock::elapsedRealtime,
            commandTimeoutMillis = 25_000, settingsReadTimeoutMillis = 6_000,
        )
        session?.onConnected()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (stopped) return
            val g = gateway
            if (g?.hasCompletedRead == true) {
                sdkReadSucceeded = g.completedReadSucceeded
                if (restoreMode) g.releaseCompletedRead()
            }
            session?.tick()
            if (session == null && SystemClock.elapsedRealtime() - elapsedStart > 30_000) {
                phase = "接続・基準取得期限超過"; stop(); render(); return
            }
            render()
            handler.postDelayed(this, 250)
        }
    }

    private fun render() {
        val s = session
        report.text = listOf(
            phase,
            "実SDK読出し成功：$sdkReadSucceeded",
            "状態：${when(s?.state) {
                BleSymbologySessionState.LoadingSettings -> "設定読出し保留"
                BleSymbologySessionState.AwaitingTransportReset -> "期限超過・切断待ち"
                BleSymbologySessionState.Restoring -> "復元中"
                is BleSymbologySessionState.Failed -> "復元失敗"
                BleSymbologySessionState.Ready -> "Ready"
                null -> "未開始"
                else -> "その他"
            }}",
            "Ready：${s?.configurationState?.isReady == true}",
            "物理切断確認：$physicalClosed",
            "リンク保持：${transport?.isLinkActive == true}",
            "期限後の応答配信：$releasedLate",
            "復元用基準保持：${selected?.id?.let { store?.load(it) } != null}",
            "設定・照明書込み：SDKへ送信しません",
        ).joinToString("\n")
    }
    private fun stop() {
        if (stopped || closing) return
        closing = true
        handler.removeCallbacks(ticker)
        gateway?.stopDiscovery()
        val device = selected
        if (transport?.isLinkActive == true && device != null) {
            if (transport?.disconnect(device) != true) {
                phase = "切断要求拒否：アプリ終了が必要"
            }
        } else finishStop()
    }
    private fun finishStop() {
        stopped = true; closing = false; runGeneration++
        transport?.close(); transport = null; gateway = null
        choices.removeAllViews()
        phase = "検証終了・リンク解放済み"
    }
    override fun onStop() { stop(); super.onStop() }
}
