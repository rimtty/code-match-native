import CoreBluetooth
import Combine
import Foundation
import OSLog

#if INATECK_SDK
import InateckScannerBleKit
#endif

enum ScanInputSource: String, CaseIterable, Identifiable {
    case camera
    case bluetooth

    var id: String { rawValue }

    var label: String {
        switch self {
        case .camera: AppLocalization.string("カメラ")
        case .bluetooth: AppLocalization.string("Bluetooth")
        }
    }
}

struct BluetoothScannerDevice: Identifiable, Hashable {
    let id: String
    let name: String
}

enum BluetoothScannerConnectionState: Equatable {
    case idle
    case searching
    case connecting(BluetoothScannerDevice)
    case connected(BluetoothScannerDevice)
    case unavailable(String)
    case failed(String)

    var connectedDevice: BluetoothScannerDevice? {
        guard case .connected(let device) = self else { return nil }
        return device
    }

    var statusText: String {
        switch self {
        case .idle: AppLocalization.string("未接続")
        case .searching: AppLocalization.string("スキャナを検索中…")
        case .connecting(let device): AppLocalization.string("\(device.name)へ接続中…")
        case .connected(let device): AppLocalization.string("\(device.name) 接続済み")
        case .unavailable(let message), .failed(let message): message
        }
    }
}

enum BluetoothScannerConfigurationState: Equatable {
    case unavailable
    case configuring
    case ready
    case failed(String)
}

enum BluetoothScannerSymbologyMode: String, Equatable {
    case unrestricted
    case sessionCodes
    // 旧バージョンが制限中に終了した場合の復旧互換用。新しい照合では使用しない。
    case qrOnly
    case code128Only

    init(expectedCode: ExpectedCode?) {
        switch expectedCode {
        case .qr, .barcode: self = .sessionCodes
        case nil: self = .unrestricted
        }
    }

    var statusText: String {
        switch self {
        case .unrestricted:
            AppLocalization.string("読取対象：接続前の設定へ復元済み")
        case .sessionCodes:
            AppLocalization.string("読取対象：QR・Code 128（照合セッション）")
        case .qrOnly:
            AppLocalization.string("読取対象：QRのみ（旧設定から復旧中）")
        case .code128Only:
            AppLocalization.string("読取対象：Code 128のみ（旧設定から復旧中）")
        }
    }
}

struct BluetoothScannerSymbologySnapshot: Codable, Equatable {
    let deviceID: String
    let values: [String: Int]
}

struct BluetoothScannerDiagnosticEvent: Identifiable, Equatable, Codable {
    let id = UUID()
    let date: Date
    let message: String
}

/// Inateck SDKへの依存をアプリの残りから隔離する接続サービス。
/// Simulatorでは同じAPIのモックとして動作し、実機ビルドだけが公式SDKを使用する。
@MainActor
final class BluetoothScannerService: NSObject, ObservableObject {
    static let preferredDeviceIDKey = "bluetoothScanner.preferredDeviceID"
    static let symbologyRecoveryModeKey = "bluetoothScanner.symbologyRecoveryMode"
    static let symbologySnapshotKey = "bluetoothScanner.symbologySnapshot"
    static let diagnosticEventsKey = "bluetoothScanner.diagnosticEvents"
    static let cachedScannerSettingsKey = "bluetoothScanner.cachedScannerSettings"
    static let lastKnownDeviceIDKey = "bluetoothScanner.lastKnownDeviceID"
    static let lastKnownDeviceNameKey = "bluetoothScanner.lastKnownDeviceName"
    static let discoveryTimeout: TimeInterval = 5
    static let connectionTimeout: TimeInterval = 30
    static let automaticReconnectTimeout: TimeInterval = 8
    static let duplicateInterval: TimeInterval = 0.75
    static let symbologyCommandTimeout: Duration = .seconds(3)
    static let settingsRetryLimit = 4

    @Published private(set) var devices: [BluetoothScannerDevice] = []
    @Published private(set) var state: BluetoothScannerConnectionState = .idle
    @Published private(set) var configurationState: BluetoothScannerConfigurationState = .unavailable
    @Published private(set) var diagnosticEvents: [BluetoothScannerDiagnosticEvent] = []

    var onCode: ((String) -> Void)?

    private let defaults: UserDefaults
    private let now: () -> Date
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier ?? "jp.rimtty.CodeMatch",
        category: "BluetoothScanner"
    )
    private var reconnectDeviceID: String?
    private var lastDeliveredCode: (value: String, date: Date)?
    private(set) var expectedCode: ExpectedCode?

#if INATECK_SDK
    private var sdkDevices: [String: BLEDevice] = [:]
    private var connectedSDKDevice: BLEDevice?
    private var availabilityMonitor: CBCentralManager?
    private var pendingDiscovery = false
    private var sdkDiscoveryIsRunning = false
    private var applicationIsActive = false
    private var automaticReconnectAttempt = 0
    private var automaticReconnectTask: Task<Void, Never>?
    private var gattModeChangeInProgress = false
    private var sdkOutputConfigurationPeripheral: CBPeripheral?
    private var sdkOutputConfigurationInProgress = false
    private var sdkOutputConfigurationCompleted = false
    private var connectedScannerSettings: String?
    private var connectedSymbologyValues: [String: Int]?
    private var connectedScannerSettingsAreFresh = false
    private var symbologyConfigurationRevision = 0
    private var symbologyCommandInFlight = false
    private var symbologyCommandGeneration = 0
    private var symbologyCommandTimeoutTask: Task<Void, Never>?
    private var manualDisconnectInProgress = false
#endif

    var connectedDevice: BluetoothScannerDevice? { state.connectedDevice }
    var isConnected: Bool { connectedDevice != nil }
    var isReadyForScanning: Bool { isConnected && configurationState == .ready }
    var reconnectableDevice: BluetoothScannerDevice? {
        guard let id = defaults.string(forKey: Self.lastKnownDeviceIDKey), !id.isEmpty else {
            return nil
        }
        return devices.first(where: { $0.id == id })
            ?? BluetoothScannerDevice(
                id: id,
                name: defaults.string(forKey: Self.lastKnownDeviceNameKey) ?? "Inateck Scanner"
            )
    }

    var persistedSymbologyMode: BluetoothScannerSymbologyMode {
        guard let rawValue = defaults.string(forKey: Self.symbologyRecoveryModeKey),
              let mode = BluetoothScannerSymbologyMode(rawValue: rawValue) else {
            return .unrestricted
        }
        return mode
    }

    var persistedSymbologySnapshot: BluetoothScannerSymbologySnapshot? {
        guard let data = defaults.data(forKey: Self.symbologySnapshotKey) else { return nil }
        return try? JSONDecoder().decode(BluetoothScannerSymbologySnapshot.self, from: data)
    }

    init(
        defaults: UserDefaults = .standard,
        now: @escaping () -> Date = Date.init
    ) {
        self.defaults = defaults
        self.now = now
        super.init()

        if ProcessInfo.processInfo.arguments.contains("-resetBluetoothScanner") {
            defaults.removeObject(forKey: Self.preferredDeviceIDKey)
            defaults.removeObject(forKey: Self.symbologyRecoveryModeKey)
            defaults.removeObject(forKey: Self.symbologySnapshotKey)
            defaults.removeObject(forKey: Self.diagnosticEventsKey)
            defaults.removeObject(forKey: Self.cachedScannerSettingsKey)
            defaults.removeObject(forKey: Self.lastKnownDeviceIDKey)
            defaults.removeObject(forKey: Self.lastKnownDeviceNameKey)
        } else if let data = defaults.data(forKey: Self.diagnosticEventsKey),
                  let events = try? JSONDecoder().decode(
                    [BluetoothScannerDiagnosticEvent].self,
                    from: data
                  ) {
            diagnosticEvents = Array(events.suffix(20))
        }

        migrateLastKnownDeviceFromDiagnosticsIfNeeded()

#if INATECK_SDK
        trace("Inateck SDK implementation initialized")
        BLEManager.shared.disconnectHandler = { [weak self] device in
            Task { @MainActor in
                self?.handleSDKDisconnect(device)
            }
        }
        availabilityMonitor = CBCentralManager(delegate: self, queue: nil)
#else
        trace("Simulator mock implementation initialized")
        if ProcessInfo.processInfo.arguments.contains("-demoBluetoothConnected") {
            let device = Self.simulatorDevice
            devices = [device]
            state = .connected(device)
            configurationState = .ready
            clearPersistedSymbologySnapshot()
            recordAppliedSymbologyMode(.unrestricted)
            defaults.set(device.id, forKey: Self.preferredDeviceIDKey)
            rememberDevice(device)
        }
#endif
    }

    func startDiscovery() {
        trace("Discovery requested")
#if INATECK_SDK
        guard !sdkDiscoveryIsRunning else {
            trace("Discovery request ignored: scan already running")
            return
        }
        guard bluetoothIsAuthorized else { return }
        guard let availabilityMonitor, availabilityMonitor.state == .poweredOn else {
            pendingDiscovery = true
            switch availabilityMonitor?.state {
            case .poweredOff:
                state = .unavailable(AppLocalization.string("Bluetoothがオフです。"))
            case .unauthorized:
                state = .unavailable(AppLocalization.string("Bluetoothの利用が許可されていません。"))
            case .unsupported:
                state = .unavailable(AppLocalization.string("この端末ではBluetoothを利用できません。"))
            case .resetting:
                state = .unavailable(AppLocalization.string("Bluetoothを再準備しています…"))
            case .unknown, .none:
                state = .searching
            case .poweredOn:
                break
            @unknown default:
                state = .unavailable(AppLocalization.string("Bluetoothの状態を取得できません。"))
            }
            trace("Discovery deferred until Core Bluetooth is ready")
            return
        }

        pendingDiscovery = false
        sdkDiscoveryIsRunning = true
        devices = []
        mergeSDKCachedDevices()
        appendReconnectableDeviceIfNeeded()
        state = .searching

        BLEManager.shared.scanDevices(timeoutAfter: Self.discoveryTimeout) { [weak self] scanState in
            Task { @MainActor in
                self?.handleSDKScanState(scanState)
            }
        }
#else
        state = .searching
        let device = Self.simulatorDevice
        devices = [device]
        state = .idle

        if reconnectDeviceID == device.id {
            reconnectDeviceID = nil
            connect(device)
        }
#endif
    }

    func stopDiscovery() {
        trace("Discovery stop requested")
#if INATECK_SDK
        pendingDiscovery = false
        sdkDiscoveryIsRunning = false
        BLEManager.shared.stopScan()
#endif
        if case .searching = state {
            state = .idle
        }
    }

    func connect(
        _ device: BluetoothScannerDevice,
        timeout: TimeInterval = BluetoothScannerService.connectionTimeout
    ) {
        trace("Connect requested: \(device.name) [\(device.id)]")
#if INATECK_SDK
        guard let sdkDevice = sdkDevices[device.id]
                ?? BLEManager.shared.devices.first(where: { $0.uuid == device.id }) else {
            rememberDevice(device)
            reconnectDeviceID = device.id
            trace("Known scanner is not in the SDK cache; discovering before reconnect")
            startDiscovery()
            return
        }

        automaticReconnectTask?.cancel()
        automaticReconnectTask = nil
        reconnectDeviceID = nil
        state = .connecting(device)
        // The first connection can present an iOS bonding dialog. Five seconds
        // is enough for scanning, but not for the user to approve that dialog
        // and for the SDK to finish service discovery.
        sdkDevice.connect(timeout: timeout) { [weak self] value in
            Task { @MainActor in
                guard let self else { return }
                self.trace("SDK scan callback invoked (\(value.utf8.count) UTF-8 bytes)")
                guard let payload = Self.decodedSDKScanPayload(value) else {
                    self.trace("SDK callback ignored because it was not a complete scan payload")
                    return
                }
                self.trace("SDK scan payload decoded (\(payload.utf8.count) UTF-8 bytes)")
                self.receiveCode(payload)
            }
        } completion: { [weak self] result in
            Task { @MainActor in
                guard let self else { return }
                switch result {
                case .success:
                    self.connectedSDKDevice = sdkDevice
                    self.sdkOutputConfigurationCompleted = false
                    self.connectedScannerSettings = nil
                    self.connectedSymbologyValues = nil
                    self.connectedScannerSettingsAreFresh = false
                    self.symbologyCommandGeneration += 1
                    self.symbologyCommandInFlight = false
                    self.symbologyCommandTimeoutTask?.cancel()
                    self.symbologyCommandTimeoutTask = nil
                    self.manualDisconnectInProgress = false
                    self.configurationState = .configuring
                    self.defaults.set(device.id, forKey: Self.preferredDeviceIDKey)
                    self.rememberDevice(device)
                    self.state = .connected(device)
                    self.automaticReconnectAttempt = 0
                    self.trace("Connected: \(device.name) [\(device.id)]")
                    self.ensureGATTMode(for: sdkDevice, appDevice: device)
                case .failure(let error):
                    self.connectedSDKDevice = nil
                    self.configurationState = .unavailable
                    self.state = .failed(
                        AppLocalization.string("接続できませんでした: \(error.localizedDescription)")
                    )
                    self.trace("Connection failed: \(error.localizedDescription)")
                    self.scheduleAutomaticReconnect(reason: "connection failure")
                }
            }
        }
#else
        reconnectDeviceID = nil
        if !devices.contains(device) { devices.append(device) }
        state = .connecting(device)
        defaults.set(device.id, forKey: Self.preferredDeviceIDKey)
        rememberDevice(device)
        state = .connected(device)
        configurationState = .ready
        let mode = BluetoothScannerSymbologyMode(expectedCode: expectedCode)
        if mode == .unrestricted {
            clearPersistedSymbologySnapshot()
        } else {
            persistSimulatorSymbologySnapshotIfNeeded()
            recordPendingSymbologyMode(mode)
        }
        recordAppliedSymbologyMode(mode)
#endif
    }

    func disconnect() {
        trace("Manual disconnect requested")
#if INATECK_SDK
        automaticReconnectTask?.cancel()
        automaticReconnectTask = nil
        automaticReconnectAttempt = 0
#endif
        reconnectDeviceID = nil
        defaults.removeObject(forKey: Self.preferredDeviceIDKey)
        expectedCode = nil

#if INATECK_SDK
        symbologyConfigurationRevision += 1
        guard let sdkDevice = connectedSDKDevice else {
            configurationState = .unavailable
            state = .idle
            return
        }
        manualDisconnectInProgress = true
        restoreSafeBaselineBeforeDisconnect(sdkDevice)
#else
        clearPersistedSymbologySnapshot()
        recordAppliedSymbologyMode(.unrestricted)
        configurationState = .unavailable
        state = .idle
#endif
    }

    func reconnectPreferredDevice() {
        guard !isConnected,
              reconnectDeviceID == nil,
              let preferredID = defaults.string(forKey: Self.preferredDeviceIDKey),
              !preferredID.isEmpty else { return }

#if INATECK_SDK
        if let cachedSDKDevice = sdkDevices[preferredID]
                ?? BLEManager.shared.devices.first(where: { $0.uuid == preferredID }) {
            let device = BluetoothScannerDevice(
                id: cachedSDKDevice.uuid,
                name: cachedSDKDevice.name ?? cachedSDKDevice.productName ?? "Inateck Scanner"
            )
            sdkDevices[device.id] = cachedSDKDevice
            trace(
                "Preferred-device reconnect using SDK cache: \(device.name) "
                    + "[\(device.id)] state=\(String(describing: cachedSDKDevice.connectState))"
            )
            connect(device, timeout: Self.automaticReconnectTimeout)
            return
        }
#endif

        reconnectDeviceID = preferredID
        trace("Preferred-device reconnect requested: \(preferredID)")
        startDiscovery()
    }

    func reconnectKnownDevice() {
        guard !isConnected, let device = reconnectableDevice else {
            if !isConnected {
                state = .failed(AppLocalization.string("再接続できるスキャナがありません。スキャナを検索してください。"))
            }
            return
        }
        trace("Manual reconnect requested for known scanner: \(device.name) [\(device.id)]")
        connect(device)
    }

    func setApplicationActive(_ isActive: Bool) {
#if INATECK_SDK
        applicationIsActive = isActive
        if isActive {
            automaticReconnectAttempt = 0
            reconnectPreferredDevice()
        } else {
            automaticReconnectTask?.cancel()
            automaticReconnectTask = nil
            if sdkDiscoveryIsRunning {
                stopDiscovery()
            }
        }
#else
        if isActive {
            reconnectPreferredDevice()
        }
#endif
    }

    /// 現在の業務工程に合わせ、実機が報告した全バーコード種別から
    /// 照合中はQRとCode 128の両方だけを有効にする。工程間では設定を書き換えず、
    /// 読み取り順序はアプリ側で検証する。`nil`では接続前の設定を正確に復元する。
    func setExpectedCode(_ expectedCode: ExpectedCode?) {
        let mode = BluetoothScannerSymbologyMode(expectedCode: expectedCode)
        let previousExpectedCode = self.expectedCode
        let previousMode = BluetoothScannerSymbologyMode(expectedCode: previousExpectedCode)
        self.expectedCode = expectedCode

#if INATECK_SDK
        // QR→Code 128やQR読み直しは論理工程だけを更新する。同じsessionCodesを
        // 適用中なら追加コマンドを積まず、実行中のGATT処理とトリガー入力を競合させない。
        if previousMode == mode, symbologyCommandInFlight {
            if previousExpectedCode != expectedCode {
                trace("Logical scan step changed while scanner session mode is still configuring")
            }
            return
        }
#endif

        // 物理モードが同じなら論理工程が変わっても再送しない。照合中の
        // setSettingInfoはセッション開始時の1回だけに限定する。
        guard !isReadyForScanning || persistedSymbologyMode != mode else {
            if previousExpectedCode != expectedCode, previousMode == mode {
                trace("Logical scan step changed; scanner remains in \(mode.rawValue)")
            }
            return
        }

#if INATECK_SDK
        symbologyConfigurationRevision += 1
        guard connectedSDKDevice != nil else {
            configurationState = .unavailable
            return
        }
        configurationState = .configuring
        guard sdkOutputConfigurationCompleted else { return }
        applyExpectedCodeConfiguration(revision: symbologyConfigurationRevision)
#else
        guard isConnected else {
            configurationState = .unavailable
            return
        }
        if mode == .unrestricted {
            clearPersistedSymbologySnapshot()
        } else {
            persistSimulatorSymbologySnapshotIfNeeded()
            recordPendingSymbologyMode(mode)
        }
        recordAppliedSymbologyMode(mode)
        configurationState = .ready
#endif
    }

    /// SimulatorのUIテストと単体テストだけで使用する入力フック。
    func simulateScan(_ value: String) {
#if !INATECK_SDK
        guard isConnected else { return }
        receiveCode(value)
#endif
    }

    private func receiveCode(_ rawValue: String) {
        guard isReadyForScanning else {
            trace("Scan callback ignored because scanner configuration is not ready")
            return
        }
        let value = Self.normalizedPayload(rawValue)
        guard !value.isEmpty else { return }

        let receivedAt = now()
        if let lastDeliveredCode,
           lastDeliveredCode.value == value,
           receivedAt.timeIntervalSince(lastDeliveredCode.date) < Self.duplicateInterval {
            return
        }

        lastDeliveredCode = (value, receivedAt)
        trace("Scan callback accepted (\(value.utf8.count) UTF-8 bytes)")
        onCode?(value)
    }

    static func normalizedPayload(_ rawValue: String) -> String {
        var value = rawValue
        while let last = value.unicodeScalars.last,
              last == "\r" || last == "\n" || last.value == 0 {
            value.removeLast()
        }
        return value
    }

    /// The pinned iOS SDK returns the parser library's JSON result rather than
    /// the scan text shown in its demo. Keep accepting a direct string for
    /// compatibility with SDK revisions that already unwrap the scan value.
    static func decodedSDKScanPayload(_ callbackValue: String) -> String? {
        guard let data = callbackValue.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data),
              let root = object as? [String: Any] else {
            return callbackValue
        }

        func integer(_ value: Any?) -> Int? {
            if let number = value as? NSNumber { return number.intValue }
            if let string = value as? String { return Int(string) }
            return nil
        }

        // InateckScannerBleKit 2024-05-20 returns this final parser shape.
        // Requiring source_code as well avoids interpreting an ordinary JSON
        // barcode containing a generic `code` field as an SDK envelope.
        if root["source_code"] is String {
            guard integer(root["status"]) == 0,
                  let code = root["code"] as? String else { return nil }
            return code
        }

        // Also accept the scanner_lib notification envelope so this remains
        // compatible if a future SDK forwards the parser's intermediate JSON.
        guard root["notify_type"] != nil else { return callbackValue }
        guard integer(root["notify_type"]) == 1,
              integer(root["notify_status"]) == 1,
              let values = root["notify_data"] as? [Any] else { return nil }

        var bytes: [UInt8] = []
        bytes.reserveCapacity(values.count)
        for value in values {
            guard let integerValue = integer(value), (0...255).contains(integerValue) else {
                return nil
            }
            bytes.append(UInt8(integerValue))
        }
        return String(data: Data(bytes), encoding: .utf8)
    }

    private func rememberDevice(_ device: BluetoothScannerDevice) {
        defaults.set(device.id, forKey: Self.lastKnownDeviceIDKey)
        defaults.set(device.name, forKey: Self.lastKnownDeviceNameKey)
        appendDeviceIfNeeded(device)
    }

    /// 既存版で手動切断した直後はpreferred IDが消えており、専用の最終接続端末キーも
    /// まだ存在しない。過去の「接続成功」診断だけを移行元にして、アップデート後も
    /// 電源OFF／ONやGATT設定のやり直しなしで再接続候補を表示する。
    private func migrateLastKnownDeviceFromDiagnosticsIfNeeded() {
        guard defaults.string(forKey: Self.lastKnownDeviceIDKey) == nil else { return }

        let prefix = "Connected: "
        for event in diagnosticEvents.reversed() where event.message.hasPrefix(prefix) {
            guard event.message.hasSuffix("]"),
                  let bracket = event.message.lastIndex(of: "[") else { continue }

            let idStart = event.message.index(after: bracket)
            let idEnd = event.message.index(before: event.message.endIndex)
            let id = String(event.message[idStart..<idEnd])
            guard UUID(uuidString: id) != nil else { continue }

            let nameStart = event.message.index(event.message.startIndex, offsetBy: prefix.count)
            let rawName = event.message[nameStart..<bracket]
            let name = rawName.trimmingCharacters(in: .whitespaces)
            guard !name.isEmpty else { continue }

            rememberDevice(BluetoothScannerDevice(id: id, name: name))
            return
        }
    }

    private func appendReconnectableDeviceIfNeeded() {
        guard let device = reconnectableDevice else { return }
        appendDeviceIfNeeded(device)
    }

    private func appendDeviceIfNeeded(_ device: BluetoothScannerDevice) {
        if let index = devices.firstIndex(where: { $0.id == device.id }) {
            devices[index] = device
        } else {
            devices.append(device)
        }
    }

#if INATECK_SDK
    private func mergeSDKCachedDevices() {
        for sdkDevice in BLEManager.shared.devices {
            let device = BluetoothScannerDevice(
                id: sdkDevice.uuid,
                name: sdkDevice.name ?? sdkDevice.productName ?? "Inateck Scanner"
            )
            sdkDevices[device.id] = sdkDevice
            appendDeviceIfNeeded(device)
        }
    }

    private var bluetoothIsAuthorized: Bool {
        switch CBManager.authorization {
        case .denied, .restricted:
            state = .unavailable(
                AppLocalization.string("Bluetoothの利用が許可されていません。設定アプリで許可してください。")
            )
            return false
        case .allowedAlways, .notDetermined:
            return true
        @unknown default:
            state = .unavailable(AppLocalization.string("Bluetoothを利用できません。"))
            return false
        }
    }

    private func handleSDKScanState(_ scanState: BLEManager.ScanStatus) {
        switch scanState {
        case .start:
            sdkDiscoveryIsRunning = true
            state = .searching
            trace("SDK discovery started")
        case .scan(let sdkDevice):
            let device = BluetoothScannerDevice(
                id: sdkDevice.uuid,
                name: sdkDevice.name ?? sdkDevice.productName ?? "Inateck Scanner"
            )
            sdkDevices[device.id] = sdkDevice
            trace("SDK discovered: \(device.name) [\(device.id)]")
            if let index = devices.firstIndex(where: { $0.id == device.id }) {
                devices[index] = device
            } else {
                devices.append(device)
            }

            if reconnectDeviceID == device.id {
                reconnectDeviceID = nil
                BLEManager.shared.stopScan()
                connect(device, timeout: Self.automaticReconnectTimeout)
            }
        case .stop:
            sdkDiscoveryIsRunning = false
            if case .searching = state {
                state = .idle
            }
            reconnectDeviceID = nil
            trace("SDK discovery stopped with \(devices.count) device(s)")
            if !isConnected, !isConnecting {
                scheduleAutomaticReconnect(reason: "preferred device not found")
            }
        @unknown default:
            sdkDiscoveryIsRunning = false
            state = .failed(AppLocalization.string("Bluetoothスキャナの検索状態を取得できませんでした。"))
            reconnectDeviceID = nil
            scheduleAutomaticReconnect(reason: "unknown discovery state")
        }
    }

    private func handleSDKDisconnect(_ sdkDevice: BLEDevice) {
        guard connectedSDKDevice?.uuid == sdkDevice.uuid
                || connectedDevice?.id == sdkDevice.uuid else { return }
        cancelSDKOutputConfiguration()
        connectedSDKDevice = nil
        sdkOutputConfigurationCompleted = false
        connectedScannerSettings = nil
        connectedSymbologyValues = nil
        connectedScannerSettingsAreFresh = false
        symbologyCommandGeneration += 1
        symbologyCommandInFlight = false
        symbologyCommandTimeoutTask?.cancel()
        symbologyCommandTimeoutTask = nil
        configurationState = .unavailable
        if manualDisconnectInProgress {
            state = .idle
            trace("Manual disconnect completed")
            return
        }
        if gattModeChangeInProgress {
            gattModeChangeInProgress = false
            state = .idle
            trace("Disconnected while applying GATT mode; reconnecting: \(sdkDevice.uuid)")
            scheduleAutomaticReconnect(reason: "GATT mode applied")
        } else {
            state = .failed(AppLocalization.string("Bluetoothスキャナとの接続が切れました。"))
            trace("Unexpected disconnect: \(sdkDevice.uuid)")
            scheduleAutomaticReconnect(reason: "unexpected disconnect")
        }
    }

    private func ensureGATTMode(
        for sdkDevice: BLEDevice,
        appDevice: BluetoothScannerDevice,
        settingsAttempt: Int = 0
    ) {
        sdkDevice.messageManager.getSettingInfo { [weak self] result in
            Task { @MainActor in
                guard let self, self.connectedSDKDevice?.uuid == sdkDevice.uuid else { return }
                switch result {
                case .success(let settings):
                    guard Self.hasRequiredSymbologySettings(settings) else {
                        self.retryScannerSettings(
                            for: sdkDevice,
                            appDevice: appDevice,
                            after: settingsAttempt,
                            reason: "incomplete response"
                        )
                        return
                    }
                    self.defaults.set(settings, forKey: Self.cachedScannerSettingsKey)
                    self.continueScannerConfiguration(
                        with: settings,
                        sdkDevice: sdkDevice,
                        appDevice: appDevice,
                        settingsAreFresh: true
                    )
                case .failure(let error):
                    self.trace("Bluetooth mode query failed: \(error.localizedDescription)")
                    self.retryScannerSettings(
                        for: sdkDevice,
                        appDevice: appDevice,
                        after: settingsAttempt,
                        reason: "query failure"
                    )
                }
            }
        }
    }

    private func retryScannerSettings(
        for sdkDevice: BLEDevice,
        appDevice: BluetoothScannerDevice,
        after attempt: Int,
        reason: String
    ) {
        if attempt >= Self.settingsRetryLimit {
            if let cachedSettings = defaults.string(forKey: Self.cachedScannerSettingsKey),
               Self.hasRequiredSymbologySettings(cachedSettings) {
                trace("Scanner settings unavailable; using cached setting areas")
                continueScannerConfiguration(
                    with: cachedSettings,
                    sdkDevice: sdkDevice,
                    appDevice: appDevice,
                    settingsAreFresh: false
                )
            } else {
                configurationState = .failed(
                    AppLocalization.string("スキャナーの全バーコード設定を取得できませんでした。電源を入れ直してください。")
                )
                trace("Scanner settings unavailable after \(attempt + 1) attempts")
            }
            return
        }

        let nextAttempt = attempt + 1
        trace("Scanner settings \(reason); retrying (attempt \(nextAttempt + 1))")
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled, let self,
                  self.connectedSDKDevice?.uuid == sdkDevice.uuid else { return }
            self.ensureGATTMode(
                for: sdkDevice,
                appDevice: appDevice,
                settingsAttempt: nextAttempt
            )
        }
    }

    private func continueScannerConfiguration(
        with settings: String,
        sdkDevice: BLEDevice,
        appDevice: BluetoothScannerDevice,
        settingsAreFresh: Bool
    ) {
        connectedScannerSettings = settings
        connectedSymbologyValues = Self.symbologySettingValues(from: settings)
        connectedScannerSettingsAreFresh = settingsAreFresh
        migrateLegacySymbologyRecoveryIfNeeded(
            deviceID: sdkDevice.uuid,
            settingsAreFresh: settingsAreFresh
        )
        trace(
            "Scanner reported \(connectedSymbologyValues?.count ?? 0) barcode types"
                + (settingsAreFresh ? "" : " (cached metadata)")
        )
        let inventoryMode = Self.settingValue(named: "inventory_mode", from: settings)
        let automaticCacheUpload = Self.settingValue(named: "auto_upload_cache", from: settings)
        let clearCacheAtStartup = Self.settingValue(named: "start_up_clean_cache", from: settings)
        trace(
            "Scanner data modes: inventory=\(inventoryMode.map(String.init) ?? "unknown") "
                + "autoUpload=\(automaticCacheUpload.map(String.init) ?? "unknown") "
                + "clearAtStartup=\(clearCacheAtStartup.map(String.init) ?? "unknown")"
        )
        guard let mode = Self.bluetoothMode(from: settings) else {
            trace("Could not determine the scanner Bluetooth mode")
            beginSDKOutputConfiguration(deviceID: sdkDevice.uuid)
            return
        }
        guard mode != 2 else {
            gattModeChangeInProgress = false
            trace("Scanner Bluetooth mode confirmed: GATT (2)")
            beginSDKOutputConfiguration(deviceID: sdkDevice.uuid)
            return
        }

        gattModeChangeInProgress = true
        state = .connecting(appDevice)
        trace("Scanner Bluetooth mode is \(mode); applying GATT mode (2)")
        let command = """
        [{"area":"1","value":"0","name":"bt_mode_low"},\
        {"area":"31","value":"1","name":"bt_mode_high"}]
        """
        sdkDevice.messageManager.setSettingInfo(with: command) { [weak self] updateResult in
            Task { @MainActor in
                guard let self else { return }
                switch updateResult {
                case .success:
                    self.trace("GATT mode setting accepted; restarting scanner")
                    sdkDevice.messageManager.setRestart { [weak self] restartResult in
                        Task { @MainActor in
                            guard let self else { return }
                            switch restartResult {
                            case .success:
                                self.trace("Scanner restart accepted after GATT mode change")
                                self.scheduleGATTRestartFallback(
                                    for: sdkDevice,
                                    appDevice: appDevice
                                )
                            case .failure(let error):
                                self.gattModeChangeInProgress = false
                                self.state = .failed(
                                    AppLocalization.string(
                                        "GATT設定後にスキャナを再起動できませんでした: \(error.localizedDescription)"
                                    )
                                )
                                self.trace(
                                    "Scanner restart after GATT mode change failed: "
                                        + error.localizedDescription
                                )
                            }
                        }
                    }
                case .failure(let error):
                    self.gattModeChangeInProgress = false
                    self.state = .failed(AppLocalization.string("GATTモードへ切り替えられませんでした: \(error.localizedDescription)"))
                    self.trace("GATT mode setting failed: \(error.localizedDescription)")
                }
            }
        }
    }

    private func scheduleGATTRestartFallback(
        for sdkDevice: BLEDevice,
        appDevice: BluetoothScannerDevice
    ) {
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(5))
            guard !Task.isCancelled, let self,
                  self.gattModeChangeInProgress,
                  self.connectedSDKDevice?.uuid == sdkDevice.uuid else { return }

            self.trace("Scanner restart did not close the link; reconnecting explicitly")
            sdkDevice.disconnect { [weak self] _ in
                Task { @MainActor in
                    guard let self else { return }
                    self.connectedSDKDevice = nil
                    self.gattModeChangeInProgress = false
                    self.state = .idle
                    self.scheduleAutomaticReconnect(reason: "GATT mode restart fallback")
                }
            }
        }
    }

    private static func bluetoothMode(from settings: String) -> Int? {
        guard let low = settingValue(named: "bt_mode_low", from: settings),
              let high = settingValue(named: "bt_mode_high", from: settings) else { return nil }
        return low + (high << 1)
    }

    private static func settingValue(named name: String, from settings: String) -> Int? {
        guard let data = settings.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data),
              let root = object as? [String: Any],
              let values = root["data"] as? [[String: Any]] else { return nil }
        guard let value = values.first(where: { $0["name"] as? String == name })?["value"] else {
            return nil
        }
        if let string = value as? String { return Int(string) }
        if let number = value as? NSNumber { return number.intValue }
        return nil
    }

    private var isConnecting: Bool {
        if case .connecting = state { return true }
        return false
    }

    private func scheduleAutomaticReconnect(reason: String) {
        guard applicationIsActive,
              !isConnected,
              automaticReconnectTask == nil,
              defaults.string(forKey: Self.preferredDeviceIDKey)?.isEmpty == false else { return }

        automaticReconnectAttempt += 1
        let delay = min(pow(2.0, Double(automaticReconnectAttempt - 1)) * 2.0, 15.0)
        trace(
            "Automatic reconnect scheduled in \(Int(delay))s "
                + "(attempt \(automaticReconnectAttempt), \(reason))"
        )

        automaticReconnectTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled, let self else { return }
            self.automaticReconnectTask = nil
            guard self.applicationIsActive, !self.isConnected else { return }
            self.trace("Automatic reconnect attempt \(self.automaticReconnectAttempt) started")
            self.reconnectPreferredDevice()
        }
    }

    /// BCST-47 can remain configured to acknowledge scans to its display while
    /// routing the payload away from the SDK notification characteristic. The
    /// current official scanner_lib exposes this as
    /// inateck_scanner_cmd_get_hid_output(1), which generates the six bytes
    /// below. Use a short-lived write-only Core Bluetooth client so the SDK
    /// remains the sole subscriber to FF01.
    private func beginSDKOutputConfiguration(deviceID: String) {
        guard !sdkOutputConfigurationInProgress else { return }
        guard let availabilityMonitor,
              availabilityMonitor.state == .poweredOn,
              let identifier = UUID(uuidString: deviceID),
              let peripheral = availabilityMonitor.retrievePeripherals(
                withIdentifiers: [identifier]
              ).first else {
            trace("SDK-output configuration could not retrieve the connected scanner")
            sdkOutputConfigurationCompleted = true
            applyExpectedCodeConfiguration(revision: symbologyConfigurationRevision)
            return
        }

        sdkOutputConfigurationInProgress = true
        sdkOutputConfigurationPeripheral = peripheral
        peripheral.delegate = self
        trace("SDK-output configuration connecting: \(deviceID)")
        availabilityMonitor.connect(peripheral)
    }

    private func handleSDKOutputConfigurationConnect(_ peripheral: CBPeripheral) {
        trace("SDK-output configuration connected; discovering FF00")
        peripheral.discoverServices([CBUUID(string: "FF00")])
    }

    private func handleSDKOutputConfigurationServices(
        _ peripheral: CBPeripheral,
        error: Error?
    ) {
        guard error == nil,
              let service = peripheral.services?.first(where: { $0.uuid == CBUUID(string: "FF00") }) else {
            finishSDKOutputConfiguration(
                peripheral,
                message: "SDK-output configuration could not discover FF00: "
                    + (error?.localizedDescription ?? "service missing")
            )
            return
        }
        peripheral.discoverCharacteristics([CBUUID(string: "FF04")], for: service)
    }

    private func handleSDKOutputConfigurationCharacteristics(
        _ peripheral: CBPeripheral,
        service: CBService,
        error: Error?
    ) {
        guard error == nil,
              let characteristic = service.characteristics?.first(where: {
                  $0.uuid == CBUUID(string: "FF04")
              }) else {
            finishSDKOutputConfiguration(
                peripheral,
                message: "SDK-output configuration could not discover FF04: "
                    + (error?.localizedDescription ?? "characteristic missing")
            )
            return
        }

        let command = Data([0xF3, 0x03, 0x7F, 0x5E, 0x01, 0xD4])
        peripheral.writeValue(command, for: characteristic, type: .withoutResponse)
        trace("SDK-output configuration command written to FF04")
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled, let self else { return }
            self.finishSDKOutputConfiguration(
                peripheral,
                message: "SDK-output configuration write completed"
            )
        }
    }

    private func finishSDKOutputConfiguration(_ peripheral: CBPeripheral, message: String) {
        trace(message)
        sdkOutputConfigurationInProgress = false
        sdkOutputConfigurationCompleted = true
        sdkOutputConfigurationPeripheral = nil
        availabilityMonitor?.cancelPeripheralConnection(peripheral)
        applyExpectedCodeConfiguration(revision: symbologyConfigurationRevision)
    }

    private func cancelSDKOutputConfiguration() {
        guard let peripheral = sdkOutputConfigurationPeripheral else {
            sdkOutputConfigurationInProgress = false
            return
        }
        sdkOutputConfigurationInProgress = false
        sdkOutputConfigurationPeripheral = nil
        availabilityMonitor?.cancelPeripheralConnection(peripheral)
    }

    private func restoreSafeBaselineBeforeDisconnect(_ sdkDevice: BLEDevice) {
        configurationState = .configuring
        guard !symbologyCommandInFlight else {
            trace("Original symbology restore deferred until the current setting command completes")
            return
        }
        guard let snapshot = persistedSymbologySnapshot else {
            recordAppliedSymbologyMode(.unrestricted)
            performManualDisconnect(sdkDevice)
            return
        }
        guard snapshot.deviceID == sdkDevice.uuid,
              let settings = connectedScannerSettings,
              let command = Self.symbologySettingCommand(values: snapshot.values, settings: settings) else {
            trace("Original symbology restore skipped because the saved settings do not match")
            performManualDisconnect(sdkDevice)
            return
        }

        symbologyCommandGeneration += 1
        let commandGeneration = symbologyCommandGeneration
        symbologyCommandInFlight = true
        trace(
            "Restoring original scanner mode "
                + "(generation \(commandGeneration), \(snapshot.values.count) barcode types)"
        )
        scheduleBaselineRestoreTimeout(
            generation: commandGeneration,
            sdkDevice: sdkDevice
        )
        sdkDevice.messageManager.setSettingInfo(with: command) { [weak self] result in
            Task { @MainActor in
                guard let self,
                      commandGeneration == self.symbologyCommandGeneration else { return }
                self.symbologyCommandTimeoutTask?.cancel()
                self.symbologyCommandTimeoutTask = nil
                self.symbologyCommandInFlight = false
                switch result {
                case .success:
                    self.connectedSymbologyValues = snapshot.values
                    self.connectedScannerSettingsAreFresh = true
                    self.clearPersistedSymbologySnapshot()
                    self.recordAppliedSymbologyMode(.unrestricted)
                    self.trace(
                        "Restored \(snapshot.values.count) original barcode settings before disconnect"
                    )
                case .failure(let error):
                    // 元設定と制限中の記録は消さず、次回接続時の復旧を必ず再試行する。
                    self.trace(
                        "Original symbology restore before disconnect failed: "
                            + error.localizedDescription
                    )
                }
                self.performManualDisconnect(sdkDevice)
            }
        }
    }

    private func performManualDisconnect(_ sdkDevice: BLEDevice) {
        symbologyCommandTimeoutTask?.cancel()
        symbologyCommandTimeoutTask = nil
        manualDisconnectInProgress = true
        cancelSDKOutputConfiguration()
        sdkDevice.disconnect { [weak self] result in
            Task { @MainActor in
                guard let self else { return }
                self.connectedSDKDevice = nil
                self.sdkOutputConfigurationCompleted = false
                self.connectedScannerSettings = nil
                self.connectedSymbologyValues = nil
                self.connectedScannerSettingsAreFresh = false
                self.symbologyCommandGeneration += 1
                self.symbologyCommandInFlight = false
                self.symbologyCommandTimeoutTask?.cancel()
                self.symbologyCommandTimeoutTask = nil
                self.configurationState = .unavailable
                self.manualDisconnectInProgress = false
                switch result {
                case .success:
                    self.state = .idle
                    self.trace("Disconnected")
                case .failure(let error):
                    self.state = .failed(AppLocalization.string("切断に失敗しました: \(error.localizedDescription)"))
                    self.trace("Disconnect failed: \(error.localizedDescription)")
                }
            }
        }
    }

    private func applyExpectedCodeConfiguration(revision: Int) {
        guard revision == symbologyConfigurationRevision,
              let sdkDevice = connectedSDKDevice,
              let settings = connectedScannerSettings else {
            if connectedSDKDevice != nil, connectedScannerSettings == nil {
                trace("Symbology configuration deferred because scanner settings are unavailable")
            }
            return
        }
        guard !symbologyCommandInFlight else {
            trace("Symbology configuration deferred until the current setting command completes")
            return
        }

        let mode = BluetoothScannerSymbologyMode(expectedCode: expectedCode)

        if mode == .unrestricted, persistedSymbologySnapshot == nil {
            recordAppliedSymbologyMode(.unrestricted)
            configurationState = .ready
            trace("Scanner barcode settings already match the pre-session state")
            return
        }

        let snapshot: BluetoothScannerSymbologySnapshot
        if let persisted = persistedSymbologySnapshot {
            guard persisted.deviceID == sdkDevice.uuid else {
                configurationState = .failed(
                    AppLocalization.string("別のスキャナーの読み取り設定が復元待ちです。元のスキャナーへ再接続してください。")
                )
                trace("Saved symbology snapshot belongs to another scanner")
                return
            }
            snapshot = persisted
        } else {
            guard mode != .unrestricted,
                  connectedScannerSettingsAreFresh,
                  let currentValues = connectedSymbologyValues,
                  Self.hasRequiredSymbologyValues(currentValues) else {
                configurationState = .failed(
                    AppLocalization.string("スキャナーの現在の読み取り設定を保存できませんでした。電源を入れ直してください。")
                )
                trace("Fresh symbology settings are required before applying a restriction")
                return
            }
            snapshot = BluetoothScannerSymbologySnapshot(
                deviceID: sdkDevice.uuid,
                values: currentValues
            )
            persistSymbologySnapshot(snapshot)
        }

        guard let values = Self.symbologySettingValues(for: mode, original: snapshot.values),
              let command = Self.symbologySettingCommand(values: values, settings: settings) else {
            trace("Symbology configuration skipped because the complete setting set is unavailable")
            configurationState = .failed(
                AppLocalization.string("スキャナーから全バーコード種類の設定を取得できませんでした。カメラを使用してください。")
            )
            return
        }

        if mode != .unrestricted {
            recordPendingSymbologyMode(mode)
        }
        symbologyCommandGeneration += 1
        let commandGeneration = symbologyCommandGeneration
        symbologyCommandInFlight = true
        let requestedAt = ProcessInfo.processInfo.systemUptime
        trace(
            "Applying scanner mode \(mode.rawValue) "
                + "(generation \(commandGeneration), \(values.count) barcode types)"
        )
        scheduleSymbologyCommandTimeout(
            generation: commandGeneration,
            sdkDevice: sdkDevice,
            mode: mode
        )
        sdkDevice.messageManager.setSettingInfo(with: command) { [weak self] result in
            let callbackDelay = ProcessInfo.processInfo.systemUptime - requestedAt
            Task { @MainActor in
                guard let self,
                      commandGeneration == self.symbologyCommandGeneration else { return }
                self.symbologyCommandTimeoutTask?.cancel()
                self.symbologyCommandTimeoutTask = nil
                self.symbologyCommandInFlight = false
                guard self.connectedSDKDevice?.uuid == sdkDevice.uuid else { return }
                if self.manualDisconnectInProgress {
                    self.restoreSafeBaselineBeforeDisconnect(sdkDevice)
                    return
                }
                guard revision == self.symbologyConfigurationRevision else {
                    self.trace("Applying the latest symbology request after a superseded command")
                    self.applyExpectedCodeConfiguration(
                        revision: self.symbologyConfigurationRevision
                    )
                    return
                }
                print(
                    "[BluetoothScanner] Symbology SDK callback after "
                        + "\(String(format: "%.3f", callbackDelay))s"
                )
                switch result {
                case .success:
                    self.connectedSymbologyValues = values
                    self.connectedScannerSettingsAreFresh = true
                    if mode == .unrestricted {
                        self.clearPersistedSymbologySnapshot()
                    }
                    self.recordAppliedSymbologyMode(mode)
                    self.configurationState = .ready
                    self.trace(
                        "Scanner configured for \(mode.rawValue) across \(values.count) barcode types"
                    )
                case .failure(let error):
                    self.configurationState = .failed(
                        AppLocalization.string("スキャナーの読み取り設定を変更できませんでした。カメラを使用してください。")
                    )
                    self.trace("Scanner symbology configuration failed: \(error.localizedDescription)")
                }
            }
        }
        let dispatchDuration = ProcessInfo.processInfo.systemUptime - requestedAt
        print(
            "[BluetoothScanner] Symbology SDK request returned in "
                + "\(String(format: "%.3f", dispatchDuration))s"
        )
    }

    /// SDKが設定完了を返さない場合は新しいコマンドを重ねず、接続を閉じて
    /// 保存済みスナップショットからの再同期へ移る。遅れて届く旧コールバックは
    /// generation不一致で破棄する。
    private func scheduleSymbologyCommandTimeout(
        generation: Int,
        sdkDevice: BLEDevice,
        mode: BluetoothScannerSymbologyMode
    ) {
        symbologyCommandTimeoutTask?.cancel()
        symbologyCommandTimeoutTask = Task { [weak self] in
            do {
                try await Task.sleep(for: Self.symbologyCommandTimeout)
            } catch {
                return
            }
            guard !Task.isCancelled,
                  let self,
                  generation == self.symbologyCommandGeneration,
                  self.symbologyCommandInFlight,
                  self.connectedSDKDevice?.uuid == sdkDevice.uuid else { return }

            self.symbologyCommandTimeoutTask = nil
            self.symbologyCommandGeneration += 1
            self.symbologyCommandInFlight = false

            if self.manualDisconnectInProgress {
                self.configurationState = .unavailable
                self.trace(
                    "Original scanner mode restore timed out during disconnect; "
                        + "recovery snapshot retained"
                )
                self.performManualDisconnect(sdkDevice)
                return
            }

            self.cancelSDKOutputConfiguration()
            self.connectedSDKDevice = nil
            self.sdkOutputConfigurationCompleted = false
            self.connectedScannerSettings = nil
            self.connectedSymbologyValues = nil
            self.connectedScannerSettingsAreFresh = false
            self.configurationState = .unavailable
            self.state = .failed(
                AppLocalization.string("スキャナーの読み取り設定通信が完了しませんでした。安全のためカメラへ切り替え、再接続します。")
            )
            self.trace(
                "Scanner mode \(mode.rawValue) timed out "
                    + "(generation \(generation)); reconnecting"
            )

            sdkDevice.disconnect { [weak self] result in
                Task { @MainActor in
                    guard let self else { return }
                    switch result {
                    case .success:
                        self.trace("Scanner link closed after symbology timeout")
                    case .failure(let error):
                        self.trace(
                            "Scanner link close after symbology timeout failed: "
                                + error.localizedDescription
                        )
                    }
                    // 切断完了前に再接続を開始すると、同じSDKデバイス上で
                    // 古いリンクと新しい設定取得が競合するため、必ず完了後に再試行する。
                    self.scheduleAutomaticReconnect(reason: "symbology command timeout")
                }
            }
        }
    }

    /// 終了時の元設定復元もSDK応答を無期限には待たない。タイムアウト時は
    /// スナップショットを消さずに切断し、次回接続時の復旧へ引き継ぐ。
    private func scheduleBaselineRestoreTimeout(
        generation: Int,
        sdkDevice: BLEDevice
    ) {
        symbologyCommandTimeoutTask?.cancel()
        symbologyCommandTimeoutTask = Task { [weak self] in
            do {
                try await Task.sleep(for: Self.symbologyCommandTimeout)
            } catch {
                return
            }
            guard !Task.isCancelled,
                  let self,
                  generation == self.symbologyCommandGeneration,
                  self.symbologyCommandInFlight,
                  self.connectedSDKDevice?.uuid == sdkDevice.uuid else { return }

            self.symbologyCommandTimeoutTask = nil
            self.symbologyCommandGeneration += 1
            self.symbologyCommandInFlight = false
            self.configurationState = .unavailable
            self.trace(
                "Original scanner mode restore timed out "
                    + "(generation \(generation)); recovery snapshot retained"
            )
            self.performManualDisconnect(sdkDevice)
        }
    }

    /// 旧版はQR／Code 128の制限状態だけを記録し、変更前スナップショットを
    /// 持っていなかった。旧版が変更したのはこの2項目だけなので、初回の最新取得値を
    /// 基に両方をONへ戻す復旧値を作り、アップデート直後にも制限を残さない。
    private func migrateLegacySymbologyRecoveryIfNeeded(
        deviceID: String,
        settingsAreFresh: Bool
    ) {
        guard settingsAreFresh,
              persistedSymbologySnapshot == nil,
              persistedSymbologyMode != .unrestricted,
              var values = connectedSymbologyValues,
              Self.hasRequiredSymbologyValues(values) else { return }

        values["qrcode_on"] = 1
        values["code128_on"] = 1
        persistSymbologySnapshot(
            BluetoothScannerSymbologySnapshot(deviceID: deviceID, values: values)
        )
        trace("Migrated legacy QR/Code 128 recovery state to a full barcode snapshot")
    }

#else
    private static let simulatorDevice = BluetoothScannerDevice(
        id: "SIMULATOR-BCST-47",
        name: "BCST-47 (Simulator)"
    )
    private static let simulatorSymbologyValues = [
        "code39_on": 1,
        "code128_on": 1,
        "ean_13_on": 1,
        "qrcode_on": 1,
        "datamatrix_on": 1
    ]
#endif

    /// 制限を適用する前に記録し、プロセスが途中終了しても次回接続で復旧できるようにする。
    /// 元設定への復元では、SDKの成功応答を受けるまで既存の制限記録を残す。
    private func recordPendingSymbologyMode(_ mode: BluetoothScannerSymbologyMode) {
        guard mode != .unrestricted else { return }
        defaults.set(mode.rawValue, forKey: Self.symbologyRecoveryModeKey)
    }

    private func recordAppliedSymbologyMode(_ mode: BluetoothScannerSymbologyMode) {
        if mode == .unrestricted {
            defaults.removeObject(forKey: Self.symbologyRecoveryModeKey)
        } else {
            defaults.set(mode.rawValue, forKey: Self.symbologyRecoveryModeKey)
        }
    }

    private func persistSymbologySnapshot(_ snapshot: BluetoothScannerSymbologySnapshot) {
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        defaults.set(data, forKey: Self.symbologySnapshotKey)
    }

    private func clearPersistedSymbologySnapshot() {
        defaults.removeObject(forKey: Self.symbologySnapshotKey)
    }

#if !INATECK_SDK
    private func persistSimulatorSymbologySnapshotIfNeeded() {
        guard persistedSymbologySnapshot == nil, let deviceID = connectedDevice?.id else { return }
        persistSymbologySnapshot(
            BluetoothScannerSymbologySnapshot(
                deviceID: deviceID,
                values: Self.simulatorSymbologyValues
            )
        )
    }
#endif

    /// 固定している公式iOS SDKは、取得した設定の`area`と`name`を
    /// `setSettingInfo`へ返す形式を使う。機種固有のareaはハードコードしない。
    static func hasRequiredSymbologySettings(_ settings: String) -> Bool {
        guard let values = symbologySettingValues(from: settings) else { return false }
        return hasRequiredSymbologyValues(values)
    }

    static func hasRequiredSymbologyValues(_ values: [String: Int]) -> Bool {
        values["qrcode_on"] != nil && values["code128_on"] != nil
    }

    static func symbologySettingValues(from settings: String) -> [String: Int]? {
        guard let items = scannerSettingItems(from: settings) else { return nil }

        var values: [String: Int] = [:]
        for item in items where isSymbologySetting(item) {
            guard let name = item["name"] as? String,
                  let value = integerSettingValue(item["value"]),
                  value == 0 || value == 1 else { continue }
            values[name] = value
        }
        return values.isEmpty ? nil : values
    }

    static func symbologySettingValues(
        for mode: BluetoothScannerSymbologyMode,
        original: [String: Int]
    ) -> [String: Int]? {
        guard hasRequiredSymbologyValues(original) else { return nil }
        switch mode {
        case .unrestricted:
            return original
        case .sessionCodes:
            var restricted = original.mapValues { _ in 0 }
            restricted["qrcode_on"] = 1
            restricted["code128_on"] = 1
            return restricted
        case .qrOnly, .code128Only:
            var restricted = original.mapValues { _ in 0 }
            restricted[mode == .qrOnly ? "qrcode_on" : "code128_on"] = 1
            return restricted
        }
    }

    static func symbologySettingCommand(values: [String: Int], settings: String) -> String? {
        guard !values.isEmpty,
              let items = scannerSettingItems(from: settings) else { return nil }

        var commands: [[String: String]] = []
        for item in items {
            guard let name = item["name"] as? String,
                  let value = values[name] else { continue }
            guard let areaValue = item["area"] else { return nil }

            let area: String
            if let string = areaValue as? String {
                area = string
            } else if let number = areaValue as? NSNumber {
                area = number.stringValue
            } else {
                return nil
            }
            commands.append(["area": area, "value": String(value), "name": name])
        }

        guard commands.count == values.count else { return nil }

        guard let commandData = try? JSONSerialization.data(withJSONObject: commands),
              let command = String(data: commandData, encoding: .utf8) else { return nil }
        return command
    }

    private static func scannerSettingItems(from settings: String) -> [[String: Any]]? {
        guard let data = settings.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data),
              let root = object as? [String: Any] else { return nil }
        return root["data"] as? [[String: Any]] ?? root["info"] as? [[String: Any]]
    }

    private static func integerSettingValue(_ value: Any?) -> Int? {
        if let string = value as? String { return Int(string) }
        if let number = value as? NSNumber { return number.intValue }
        return nil
    }

    private static func isSymbologySetting(_ item: [String: Any]) -> Bool {
        if let flag = integerSettingValue(item["flag"]), (2001...2028).contains(flag) {
            return true
        }
        guard let name = item["name"] as? String else { return false }
        return legacySymbologySettingNames.contains(name.lowercased())
    }

    private static let legacySymbologySettingNames: Set<String> = [
        "codabar_on", "iata25_on", "interleaved25_on", "matrix25_on", "standard25_on",
        "code39_on", "code93_on", "code128_on", "ean_8_on", "ean_13_on", "upc_a_on",
        "upc_e0_on", "msi_on", "code11_on", "chinese_post_on", "upc_e1_on",
        "aztec_on", "maxicode_on", "hanxin_on", "datamatrix_on", "qrcode_on",
        "pdf417_on", "gs1_128", "rss14_composite_on", "rss_14_composite_on", "plessey_on",
        "telepen_on", "rss_14_on", "rss_expanded_on", "rss_limited_on", "symb_128_on",
        "usps_on", "usps_fedex"
    ]

    private func trace(_ message: String) {
        logger.info("\(message, privacy: .public)")
        diagnosticEvents.append(
            BluetoothScannerDiagnosticEvent(date: now(), message: message)
        )
        if diagnosticEvents.count > 20 {
            diagnosticEvents.removeFirst(diagnosticEvents.count - 20)
        }
        if let data = try? JSONEncoder().encode(diagnosticEvents) {
            defaults.set(data, forKey: Self.diagnosticEventsKey)
        }
#if DEBUG
        print("[BluetoothScanner] \(message)")
#endif
    }
}

#if INATECK_SDK
extension BluetoothScannerService: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor [weak self] in
            guard let self, !self.isConnected else { return }
            switch central.state {
            case .poweredOn:
                self.trace("Core Bluetooth powered on")
                if self.pendingDiscovery {
                    self.startDiscovery()
                } else if case .unavailable = self.state {
                    self.state = .idle
                }
            case .poweredOff:
                self.state = .unavailable(AppLocalization.string("Bluetoothがオフです。"))
                self.trace("Core Bluetooth powered off")
            case .unauthorized:
                self.state = .unavailable(AppLocalization.string("Bluetoothの利用が許可されていません。"))
                self.trace("Core Bluetooth unauthorized")
            case .unsupported:
                self.state = .unavailable(AppLocalization.string("この端末ではBluetoothを利用できません。"))
                self.trace("Core Bluetooth unsupported")
            case .resetting:
                self.state = .unavailable(AppLocalization.string("Bluetoothを再準備しています…"))
            case .unknown:
                break
            @unknown default:
                self.state = .unavailable(AppLocalization.string("Bluetoothの状態を取得できません。"))
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        Task { @MainActor [weak self] in
            guard let self,
                  self.sdkOutputConfigurationInProgress,
                  self.sdkOutputConfigurationPeripheral?.identifier == peripheral.identifier else {
                return
            }
            self.handleSDKOutputConfigurationConnect(peripheral)
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        Task { @MainActor [weak self] in
            guard let self,
                  self.sdkOutputConfigurationInProgress,
                  self.sdkOutputConfigurationPeripheral?.identifier == peripheral.identifier else {
                return
            }
            self.finishSDKOutputConfiguration(
                peripheral,
                message: "SDK-output configuration connection failed: "
                    + (error?.localizedDescription ?? "unknown error")
            )
        }
    }
}

extension BluetoothScannerService: CBPeripheralDelegate {
    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        Task { @MainActor [weak self] in
            guard let self,
                  self.sdkOutputConfigurationInProgress,
                  self.sdkOutputConfigurationPeripheral?.identifier == peripheral.identifier else {
                return
            }
            self.handleSDKOutputConfigurationServices(peripheral, error: error)
        }
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        Task { @MainActor [weak self] in
            guard let self,
                  self.sdkOutputConfigurationInProgress,
                  self.sdkOutputConfigurationPeripheral?.identifier == peripheral.identifier else {
                return
            }
            self.handleSDKOutputConfigurationCharacteristics(
                peripheral,
                service: service,
                error: error
            )
        }
    }
}
#endif
