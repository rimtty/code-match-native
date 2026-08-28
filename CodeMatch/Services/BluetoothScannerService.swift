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
        case .camera: "カメラ"
        case .bluetooth: "Bluetooth"
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
        case .idle: "未接続"
        case .searching: "スキャナを検索中…"
        case .connecting(let device): "\(device.name)へ接続中…"
        case .connected(let device): "\(device.name) 接続済み"
        case .unavailable(let message), .failed(let message): message
        }
    }
}

/// Inateck SDKへの依存をアプリの残りから隔離する接続サービス。
/// Simulatorでは同じAPIのモックとして動作し、実機ビルドだけが公式SDKを使用する。
@MainActor
final class BluetoothScannerService: NSObject, ObservableObject {
    static let preferredDeviceIDKey = "bluetoothScanner.preferredDeviceID"
    static let discoveryTimeout: TimeInterval = 5
    static let connectionTimeout: TimeInterval = 30
    static let duplicateInterval: TimeInterval = 0.75

    @Published private(set) var devices: [BluetoothScannerDevice] = []
    @Published private(set) var state: BluetoothScannerConnectionState = .idle

    var onCode: ((String) -> Void)?

    private let defaults: UserDefaults
    private let now: () -> Date
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier ?? "jp.rimtty.CodeMatch",
        category: "BluetoothScanner"
    )
    private var reconnectDeviceID: String?
    private var lastDeliveredCode: (value: String, date: Date)?

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
#endif

    var connectedDevice: BluetoothScannerDevice? { state.connectedDevice }
    var isConnected: Bool { connectedDevice != nil }

    init(
        defaults: UserDefaults = .standard,
        now: @escaping () -> Date = Date.init
    ) {
        self.defaults = defaults
        self.now = now
        super.init()

        if ProcessInfo.processInfo.arguments.contains("-resetBluetoothScanner") {
            defaults.removeObject(forKey: Self.preferredDeviceIDKey)
        }

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
            defaults.set(device.id, forKey: Self.preferredDeviceIDKey)
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
                state = .unavailable("Bluetoothがオフです。")
            case .unauthorized:
                state = .unavailable("Bluetoothの利用が許可されていません。")
            case .unsupported:
                state = .unavailable("この端末ではBluetoothを利用できません。")
            case .resetting:
                state = .unavailable("Bluetoothを再準備しています…")
            case .unknown, .none:
                state = .searching
            case .poweredOn:
                break
            @unknown default:
                state = .unavailable("Bluetoothの状態を取得できません。")
            }
            trace("Discovery deferred until Core Bluetooth is ready")
            return
        }

        pendingDiscovery = false
        sdkDiscoveryIsRunning = true
        devices = []
        sdkDevices = [:]
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

    func connect(_ device: BluetoothScannerDevice) {
        trace("Connect requested: \(device.name) [\(device.id)]")
#if INATECK_SDK
        guard let sdkDevice = sdkDevices[device.id]
                ?? BLEManager.shared.devices.first(where: { $0.uuid == device.id }) else {
            state = .failed("スキャナが見つかりません。もう一度検索してください。")
            return
        }

        automaticReconnectTask?.cancel()
        automaticReconnectTask = nil
        reconnectDeviceID = nil
        state = .connecting(device)
        // The first connection can present an iOS bonding dialog. Five seconds
        // is enough for scanning, but not for the user to approve that dialog
        // and for the SDK to finish service discovery.
        sdkDevice.connect(timeout: Self.connectionTimeout) { [weak self] value in
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
                    self.defaults.set(device.id, forKey: Self.preferredDeviceIDKey)
                    self.state = .connected(device)
                    self.automaticReconnectAttempt = 0
                    self.trace("Connected: \(device.name) [\(device.id)]")
                    self.ensureGATTMode(for: sdkDevice, appDevice: device)
                case .failure(let error):
                    self.connectedSDKDevice = nil
                    self.state = .failed("接続できませんでした: \(error.localizedDescription)")
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
        state = .connected(device)
#endif
    }

    func disconnect() {
        trace("Manual disconnect requested")
#if INATECK_SDK
        automaticReconnectTask?.cancel()
        automaticReconnectTask = nil
        automaticReconnectAttempt = 0
        cancelSDKOutputConfiguration()
#endif
        reconnectDeviceID = nil
        defaults.removeObject(forKey: Self.preferredDeviceIDKey)

#if INATECK_SDK
        guard let sdkDevice = connectedSDKDevice else {
            state = .idle
            return
        }
        sdkDevice.disconnect { [weak self] result in
            Task { @MainActor in
                guard let self else { return }
                self.connectedSDKDevice = nil
                switch result {
                case .success:
                    self.state = .idle
                    self.trace("Disconnected")
                case .failure(let error):
                    self.state = .failed("切断に失敗しました: \(error.localizedDescription)")
                    self.trace("Disconnect failed: \(error.localizedDescription)")
                }
            }
        }
#else
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
            connect(device)
            return
        }
#endif

        reconnectDeviceID = preferredID
        trace("Preferred-device reconnect requested: \(preferredID)")
        startDiscovery()
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

    /// SimulatorのUIテストと単体テストだけで使用する入力フック。
    func simulateScan(_ value: String) {
#if !INATECK_SDK
        guard isConnected else { return }
        receiveCode(value)
#endif
    }

    private func receiveCode(_ rawValue: String) {
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

#if INATECK_SDK
    private var bluetoothIsAuthorized: Bool {
        switch CBManager.authorization {
        case .denied, .restricted:
            state = .unavailable("Bluetoothの利用が許可されていません。設定アプリで許可してください。")
            return false
        case .allowedAlways, .notDetermined:
            return true
        @unknown default:
            state = .unavailable("Bluetoothを利用できません。")
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
                connect(device)
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
            state = .failed("Bluetoothスキャナの検索状態を取得できませんでした。")
            reconnectDeviceID = nil
            scheduleAutomaticReconnect(reason: "unknown discovery state")
        }
    }

    private func handleSDKDisconnect(_ sdkDevice: BLEDevice) {
        guard connectedSDKDevice?.uuid == sdkDevice.uuid
                || connectedDevice?.id == sdkDevice.uuid else { return }
        cancelSDKOutputConfiguration()
        connectedSDKDevice = nil
        if gattModeChangeInProgress {
            gattModeChangeInProgress = false
            state = .idle
            trace("Disconnected while applying GATT mode; reconnecting: \(sdkDevice.uuid)")
            scheduleAutomaticReconnect(reason: "GATT mode applied")
        } else {
            state = .failed("Bluetoothスキャナとの接続が切れました。")
            trace("Unexpected disconnect: \(sdkDevice.uuid)")
            scheduleAutomaticReconnect(reason: "unexpected disconnect")
        }
    }

    private func ensureGATTMode(for sdkDevice: BLEDevice, appDevice: BluetoothScannerDevice) {
        sdkDevice.messageManager.getSettingInfo { [weak self] result in
            Task { @MainActor in
                guard let self, self.connectedSDKDevice?.uuid == sdkDevice.uuid else { return }
                switch result {
                case .success(let settings):
                    let inventoryMode = Self.settingValue(named: "inventory_mode", from: settings)
                    let automaticCacheUpload = Self.settingValue(named: "auto_upload_cache", from: settings)
                    let clearCacheAtStartup = Self.settingValue(named: "start_up_clean_cache", from: settings)
                    self.trace(
                        "Scanner data modes: inventory=\(inventoryMode.map(String.init) ?? "unknown") "
                            + "autoUpload=\(automaticCacheUpload.map(String.init) ?? "unknown") "
                            + "clearAtStartup=\(clearCacheAtStartup.map(String.init) ?? "unknown")"
                    )
                    guard let mode = Self.bluetoothMode(from: settings) else {
                        self.trace("Could not determine the scanner Bluetooth mode")
                        self.beginSDKOutputConfiguration(deviceID: sdkDevice.uuid)
                        return
                    }
                    guard mode != 2 else {
                        self.gattModeChangeInProgress = false
                        self.trace("Scanner Bluetooth mode confirmed: GATT (2)")
                        self.beginSDKOutputConfiguration(deviceID: sdkDevice.uuid)
                        return
                    }

                    self.gattModeChangeInProgress = true
                    self.state = .connecting(appDevice)
                    self.trace("Scanner Bluetooth mode is \(mode); applying GATT mode (2)")
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
                                                "GATT設定後にスキャナを再起動できませんでした: "
                                                    + error.localizedDescription
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
                                self.state = .failed(
                                    "GATTモードへ切り替えられませんでした: \(error.localizedDescription)"
                                )
                                self.trace("GATT mode setting failed: \(error.localizedDescription)")
                            }
                        }
                    }
                case .failure(let error):
                    self.trace("Bluetooth mode query failed: \(error.localizedDescription)")
                    self.beginSDKOutputConfiguration(deviceID: sdkDevice.uuid)
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
        guard !sdkOutputConfigurationInProgress,
              let availabilityMonitor,
              availabilityMonitor.state == .poweredOn,
              let identifier = UUID(uuidString: deviceID),
              let peripheral = availabilityMonitor.retrievePeripherals(
                withIdentifiers: [identifier]
              ).first else {
            trace("SDK-output configuration could not retrieve the connected scanner")
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
        sdkOutputConfigurationPeripheral = nil
        availabilityMonitor?.cancelPeripheralConnection(peripheral)
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

#else
    private static let simulatorDevice = BluetoothScannerDevice(
        id: "SIMULATOR-BCST-47",
        name: "BCST-47 (Simulator)"
    )
#endif

    private func trace(_ message: String) {
        logger.info("\(message, privacy: .public)")
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
                self.state = .unavailable("Bluetoothがオフです。")
                self.trace("Core Bluetooth powered off")
            case .unauthorized:
                self.state = .unavailable("Bluetoothの利用が許可されていません。")
                self.trace("Core Bluetooth unauthorized")
            case .unsupported:
                self.state = .unavailable("この端末ではBluetoothを利用できません。")
                self.trace("Core Bluetooth unsupported")
            case .resetting:
                self.state = .unavailable("Bluetoothを再準備しています…")
            case .unknown:
                break
            @unknown default:
                self.state = .unavailable("Bluetoothの状態を取得できません。")
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
