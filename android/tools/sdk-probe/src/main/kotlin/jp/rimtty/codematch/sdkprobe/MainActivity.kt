package jp.rimtty.codematch.sdkprobe

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
import com.clj.fastble.BleManager
import com.inateck.scanner.ble.BleListManager
import com.inateck.scanner.ble.BleScannerDevice
import com.inateck.scanner.ble.callback.BleScanResultCallBack

/** Direct official API experiment: no app adapter, settings writes, history, or scan listener. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var layout: LinearLayout
    private lateinit var status: TextView
    private lateinit var hardware: TextView
    private lateinit var firmware: TextView
    private lateinit var devices: LinearLayout
    private val controls = mutableListOf<Button>()
    private var device: BleScannerDevice? = null
    private var generation = 0
    private var busy = false
    private var ready = false
    private var visible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BleListManager.init(application)
        BleManager.getInstance().enableLog(false)
        layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 48)
        }
        setContentView(ScrollView(this).apply { addView(layout) })
        label("Inateck SDK 2.0.0 — 独立検証", 23f)
        label("CodeMatchとは別アプリです。コードは読まず、照合アプリを閉じてから使ってください。設定の変更・保存はしません。")
        status = label("未接続")
        button("スキャナーを探す") { discover() }
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(devices)
        hardware = label("Bluetoothバージョン：未取得")
        firmware = label("本体ファームウェア：未取得")
        button("Bluetoothバージョン取得 (getHardwareInfo)") { read(true) }
        button("本体ファームウェア取得 (getVersion)") { read(false) }
        button("切断") { close("切断しました") }
        BleListManager.disconnectHandler = { disconnected, _ ->
            runOnUiThread {
                if (device !== disconnected || !visible) return@runOnUiThread
                generation++
                ready = false
                device = null
                setBusy(false)
                status.text = "切断されました。必要なら再検索してください。"
            }
        }
    }

    private fun label(value: String, size: Float = 17f): TextView = TextView(this).apply {
        text = value; textSize = size; setPadding(0, 16, 0, 16); this@MainActivity.layout.addView(this)
    }
    private fun button(value: String, action: () -> Unit) {
        val view = Button(this).apply { text = value; setOnClickListener { if (!busy) action() } }
        controls += view
        layout.addView(view)
    }
    private fun setBusy(value: Boolean) {
        busy = value
        controls.forEach { it.isEnabled = !value }
        for (index in 0 until devices.childCount) devices.getChildAt(index).isEnabled = !value
    }
    private fun permitted() = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun discover() {
        if (!permitted()) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 1)
            status.text = "権限を許可して、もう一度検索してください。"
            return
        }
        if (device != null) { status.text = "先に切断してください。"; return }
        val token = ++generation
        devices.removeAllViews()
        hardware.text = "Bluetoothバージョン：未取得"
        firmware.text = "本体ファームウェア：未取得"
        setBusy(true)
        status.text = "検索中（5秒）"
        val found = linkedMapOf<String, BleScannerDevice>()
        runCatching {
            BleListManager.scan(object : BleScanResultCallBack {
                override fun onScanStarted(scanResultList: List<BleScannerDevice>) { runOnUiThread {
                    if (token == generation && visible) scanResultList.forEach { scanner ->
                        scanner.mac?.let { found[it] = scanner }
                    }
                } }
                override fun onScanning(device: BleScannerDevice) { runOnUiThread {
                    if (token == generation && visible) device.mac?.let { found[it] = device }
                } }
                override fun onScanFinished(scanResultList: List<BleScannerDevice>) = Unit
            })
        }.onFailure { close("検索を開始できませんでした。Bluetoothと権限を確認してください。") }
        handler.postDelayed({
            if (generation == token && visible) {
                BleListManager.stopScan()
                setBusy(false)
                status.text = "接続するSDK対応スキャナーを選んでください（${found.size}件）"
                found.values.forEachIndexed { index, scanner ->
                    devices.addView(Button(this).apply {
                        // Names are visible only, never logged. Selection is not model/MAC restricted.
                        text = "${index + 1}. ${scanner.name ?: "スキャナー"}"
                        setOnClickListener { if (!busy) connect(scanner) }
                    })
                }
            }
        }, 5_000)
    }
    private fun connect(scanner: BleScannerDevice) {
        device = scanner
        ready = false
        val token = ++generation
        setBusy(true)
        status.text = "公式SDKで接続中"
        runCatching { scanner.connect { result -> runOnUiThread {
            if (token == generation && visible) {
                if (result.isSuccess) {
                    ready = true; setBusy(false); devices.removeAllViews()
                    status.text = "SDK接続成功。取得ボタンを1つずつ押してください。"
                } else close("SDK接続失敗。再検索してください。")
            }
        } } }.onFailure { close("SDK接続呼び出し失敗") }
        handler.postDelayed({ if (generation == token && busy) close("接続タイムアウト") }, 30_000)
    }
    private fun read(bluetooth: Boolean) {
        val scanner = device
        if (!ready || scanner == null) { status.text = "先に接続してください。"; return }
        val target = if (bluetooth) hardware else firmware
        val title = if (bluetooth) "Bluetoothバージョン" else "本体ファームウェア"
        val observation = if (bluetooth) null else VersionObservation { ModernVersionParser.inspect(it) }
        val token = ++generation
        val started = SystemClock.elapsedRealtime()
        setBusy(true)
        target.text = "$title：取得中…"
        val completion: (Result<String>) -> Unit = { result -> runOnUiThread {
            if (generation == token && visible) {
                generation++ // Invalidate timeout and duplicate/late callbacks.
                setBusy(false)
                val elapsed = SystemClock.elapsedRealtime() - started
                if (elapsed >= 6_000) {
                    target.text = "$title：取得タイムアウト（6秒）"
                    close("期限後の応答を破棄しました。")
                    return@runOnUiThread
                }
                val value = probeValue(result.getOrNull())
                target.text = "$title：" + when {
                    result.isFailure -> "SDK取得失敗（${elapsed}ms）。非対応か解析失敗かは未判定。"
                    value == null -> "応答形式が不正（${elapsed}ms）"
                    else -> "$value（${elapsed}ms）"
                }
                observation?.let { target.append("\n${it.summary()}") }
                status.text = "取得処理終了。設定書き込みは行っていません。"
                if (result.isFailure || value == null) close("取得に失敗したため切断します。")
            }
        } }
        runCatching {
            if (bluetooth) scanner.messager.getHardwareInfo(completion)
            else {
                scanner.messager.getVersion(completion)
                observation?.attach(scanner.messager)
            }
        }.onFailure { close("SDK取得呼び出し失敗") }
        handler.postDelayed({ if (generation == token) {
            target.text = "$title：取得タイムアウト（6秒）"
            close("タイムアウトのため切断。再接続が必要です。")
        } }, 6_000)
    }
    private fun close(message: String) {
        generation++
        ready = false
        runCatching { BleListManager.stopScan() }
        val previous = device
        device = null
        runCatching { previous?.disconnect { } }
        // Never overlap a reconnect with an uncertain SDK operation. Relaunch after timeout.
        setBusy(true)
        status.text = "$message\n検証を続ける場合はアプリを閉じて開き直してください。"
    }
    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() { visible = false; close("画面を離れたため停止しました"); super.onStop() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        BleListManager.disconnectHandler = null
        super.onDestroy()
    }
}
