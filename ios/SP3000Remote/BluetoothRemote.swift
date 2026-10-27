import Combine
import CoreBluetooth
import RemoteCore
import UIKit

/// Foreground HID-over-GATT peripheral. Delegate callbacks and UI actions run on the main queue.
/// This is an experimental transport: publishing the service and SP3000 interoperability
/// must be tested on real hardware. No private Bluetooth API or classic-HID API is used.
final class BluetoothRemote: NSObject, ObservableObject, CBPeripheralManagerDelegate {
    @Published private(set) var status = "未連線"
    @Published private(set) var isRunning = false
    @Published private(set) var canSend = false
    @Published private(set) var canSendExtended = false
    @Published private(set) var lastAction = ""
    @Published private(set) var diagnostic = ""

    private var manager: CBPeripheralManager?
    private var reports: [UInt8: CBMutableCharacteristic] = [:]
    private var subscriptions: Set<UInt8> = []
    private var central: CBCentral?
    private var values: [UInt8: Data] = [:]
    private var outbox = ReportOutbox()
    private var sendWork: DispatchWorkItem?
    private var stopWork: DispatchWorkItem?
    private var suspended = false
    private var serviceReady = false
    private var stopping = false
    private var wantsToRun = false
    private var backgrounded = false

    func start() {
        guard manager == nil else { return }
        wantsToRun = true
        isRunning = true
        stopping = false
        diagnostic = ""
        lastAction = ""
        status = "準備藍牙…"
        UIApplication.shared.isIdleTimerDisabled = true
        manager = CBPeripheralManager(delegate: self, queue: .main)
    }

    func stop() {
        wantsToRun = false
        beginStop()
    }

    func enterBackground() {
        backgrounded = true
        beginStop()
    }

    func enterForeground() {
        backgrounded = false
        if wantsToRun && manager == nil { start() }
    }

    func send(_ command: RemoteCommand) {
        let report = HIDProfile.press(command)
        guard canSend, !stopping, subscriptions.contains(report.id) else { return }
        guard outbox.enqueue(command) else {
            lastAction = "按鍵傳送中，請稍後再試"
            return
        }
        lastAction = "傳送：\(command.title)"
        flush()
    }

    private func prepareServices(_ peripheral: CBPeripheralManager) {
        peripheral.removeAllServices()
        serviceReady = false
        suspended = false
        reports.removeAll()
        subscriptions.removeAll()
        central = nil
        values.removeAll()
        outbox.reset()
        let service = CBMutableService(type: CBUUID(string: "1812"), primary: true)
        let information = CBMutableCharacteristic(
            type: CBUUID(string: "2A4A"), properties: [.read],
            value: Data([0x11, 0x01, 0, 0x02]), permissions: [.readable])
        let map = CBMutableCharacteristic(
            type: CBUUID(string: "2A4B"), properties: [.read],
            value: Data(HIDProfile.reportMap), permissions: [.readable])
        let control = CBMutableCharacteristic(
            type: CBUUID(string: "2A4C"), properties: [.writeWithoutResponse],
            value: nil, permissions: [.writeable, .writeEncryptionRequired])
        let mode = CBMutableCharacteristic(
            type: CBUUID(string: "2A4E"), properties: [.read, .writeWithoutResponse],
            value: nil, permissions: [.readable, .writeable, .writeEncryptionRequired])
        for id in [HIDProfile.mediaReportID, HIDProfile.extendedReportID] {
            let report = CBMutableCharacteristic(
                type: CBUUID(string: "2A4D"),
                properties: [.read, .notify, .notifyEncryptionRequired], value: nil,
                permissions: [.readable, .readEncryptionRequired])
            report.descriptors = [CBMutableDescriptor(
                type: CBUUID(string: "2908"), value: Data([id, 1]) as NSData)]
            reports[id] = report
            values[id] = Data(HIDProfile.neutral(id)!.bytes)
        }
        service.characteristics = [information, map, control, mode]
            + reports.keys.sorted().compactMap { reports[$0] }
        status = "啟動遙控服務…"
        peripheral.add(service)
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard peripheral === manager, !stopping else { return }
        switch peripheral.state {
        case .poweredOn:
            if !backgrounded { prepareServices(peripheral) }
        case .poweredOff:
            clearConnection()
            serviceReady = false
            status = "請開啟 iPhone 藍牙"
        case .unauthorized:
            fail("請在 iPhone 設定允許藍牙存取", detail: "Bluetooth permission denied.")
        case .unsupported:
            fail("此裝置不支援所需藍牙功能", detail: "CBPeripheralManager is unsupported.")
        case .resetting:
            clearConnection()
            serviceReady = false
            status = "藍牙重新啟動中…"
        case .unknown:
            status = "準備藍牙…"
        @unknown default:
            fail("藍牙暫時無法使用", detail: "Unknown Bluetooth state.")
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        guard peripheral === manager, !stopping, !backgrounded else { return }
        if let error {
            fail("未能啟動藍牙遙控服務", detail: describe(error))
            return
        }
        serviceReady = true
        peripheral.startAdvertising([
            CBAdvertisementDataLocalNameKey: HIDProfile.name,
            CBAdvertisementDataServiceUUIDsKey: [CBUUID(string: "1812")]
        ])
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        guard peripheral === manager, !stopping, !backgrounded else { return }
        if let error { fail("未能開始配對", detail: describe(error)) }
        else if central == nil { status = "請在 SP3000 搜尋 DAP Remote 並配對" }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central newCentral: CBCentral,
                           didSubscribeTo characteristic: CBCharacteristic) {
        guard peripheral === manager, !stopping, serviceReady,
              let id = reportID(characteristic) else { return }
        // A second subscriber must never receive the first host's media commands.
        guard central == nil || central?.identifier == newCentral.identifier else { return }
        central = newCentral
        subscriptions.insert(id)
        refreshAvailability()
        peripheral.stopAdvertising()
        // Reset host input state before accepting the user's first tap.
        outbox.replaceWithReleases(for: subscriptions)
        flush()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central oldCentral: CBCentral,
                           didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard peripheral === manager, central?.identifier == oldCentral.identifier,
              let id = reportID(characteristic) else { return }
        subscriptions.remove(id)
        sendWork?.cancel()
        outbox.replaceWithReleases(for: subscriptions)
        if subscriptions.isEmpty {
            clearConnection()
            if !stopping && !backgrounded && peripheral.state == .poweredOn {
                status = "連線已中斷，等待重新配對"
                peripheral.startAdvertising([
                    CBAdvertisementDataLocalNameKey: HIDProfile.name,
                    CBAdvertisementDataServiceUUIDsKey: [CBUUID(string: "1812")]
                ])
            }
        } else {
            refreshAvailability()
            flush()
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        guard peripheral === manager else { return }
        if let central, central.identifier != request.central.identifier {
            peripheral.respond(to: request, withResult: .insufficientAuthorization)
            return
        }
        let data: Data
        if let id = reportID(request.characteristic), let value = values[id] { data = value }
        else if request.characteristic.uuid == CBUUID(string: "2A4E") { data = Data([1]) }
        else {
            peripheral.respond(to: request, withResult: .readNotPermitted)
            return
        }
        guard request.offset <= data.count else {
            peripheral.respond(to: request, withResult: .invalidOffset)
            return
        }
        request.value = data.subdata(in: request.offset..<data.count)
        peripheral.respond(to: request, withResult: .success)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        guard peripheral === manager, let first = requests.first else { return }
        var result: CBATTError.Code = .success
        // Validate the complete batch before applying it; CoreBluetooth expects one response.
        for request in requests {
            if let central, central.identifier != request.central.identifier {
                result = .insufficientAuthorization
            } else if request.offset != 0 {
                result = .invalidOffset
            } else if request.value?.count != 1 {
                result = .invalidAttributeValueLength
            } else if request.characteristic.uuid == CBUUID(string: "2A4C") {
                if request.value![0] > 1 { result = .requestNotSupported }
            } else if request.characteristic.uuid == CBUUID(string: "2A4E") {
                // This consumer-only device supports Report Protocol, not Boot Protocol.
                if request.value![0] != 1 { result = .requestNotSupported }
            } else { result = .writeNotPermitted }
            if result != .success { break }
        }
        if result == .success {
            for request in requests where request.characteristic.uuid == CBUUID(string: "2A4C") {
                suspended = request.value![0] == 0
                if suspended {
                    outbox.replaceWithReleases(for: subscriptions)
                    flush()
                }
            }
            refreshAvailability()
        }
        peripheral.respond(to: first, withResult: result)
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        guard peripheral === manager else { return }
        flush()
    }

    private func reportID(_ characteristic: CBCharacteristic) -> UInt8? {
        reports.first(where: { $0.value === characteristic })?.key
    }

    private func refreshAvailability() {
        canSend = !stopping && !backgrounded && !suspended
            && subscriptions.contains(HIDProfile.mediaReportID)
        canSendExtended = canSend && subscriptions.contains(HIDProfile.extendedReportID)
        if suspended { status = "接收裝置已暫停遙控" }
        else if canSend { status = "已連線，可傳送遙控指令" }
        else if !subscriptions.isEmpty { status = "等待媒體控制連線…" }
    }

    private func flush() {
        sendWork?.cancel()
        guard let manager, manager.state == .poweredOn, let central else {
            if stopping { finishStop() }
            return
        }
        guard !outbox.isEmpty else {
            if stopping { finishStop() }
            return
        }
        let now = ProcessInfo.processInfo.systemUptime
        guard let report = outbox.next(at: now) else {
            scheduleSend(after: max(0, outbox.nextSendTime - now))
            return
        }
        guard subscriptions.contains(report.id), let characteristic = reports[report.id] else {
            outbox.replaceWithReleases(for: subscriptions)
            scheduleSend(after: 0)
            return
        }
        guard manager.updateValue(Data(report.bytes), for: characteristic, onSubscribedCentrals: [central]) else {
            // Preserve the press/release pair until peripheralManagerIsReady is called.
            return
        }
        values[report.id] = Data(report.bytes)
        outbox.acknowledge(at: ProcessInfo.processInfo.systemUptime)
        scheduleSend(after: outbox.interval)
    }

    private func scheduleSend(after delay: TimeInterval) {
        let work = DispatchWorkItem { [weak self] in self?.flush() }
        sendWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func beginStop() {
        guard manager != nil, !stopping else { return }
        stopping = true
        canSend = false
        canSendExtended = false
        manager?.stopAdvertising()
        outbox.replaceWithReleases(for: subscriptions)
        let work = DispatchWorkItem { [weak self] in self?.finishStop() }
        stopWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3, execute: work)
        flush()
    }

    private func finishStop() {
        sendWork?.cancel()
        stopWork?.cancel()
        manager?.stopAdvertising()
        if manager?.state == .poweredOn { manager?.removeAllServices() }
        manager?.delegate = nil
        manager = nil
        serviceReady = false
        stopping = false
        isRunning = false
        clearConnection()
        UIApplication.shared.isIdleTimerDisabled = false
        status = backgrounded ? "返回 App 後可繼續配對" : "已停止"
        if wantsToRun && !backgrounded { start() }
    }

    private func clearConnection() {
        sendWork?.cancel()
        outbox.reset()
        central = nil
        subscriptions.removeAll()
        canSend = false
        canSendExtended = false
    }

    private func fail(_ message: String, detail: String) {
        wantsToRun = false
        finishStop()
        status = message
        diagnostic = detail
    }

    private func describe(_ error: Error) -> String {
        let error = error as NSError
        return "\(error.domain) / \(error.code): \(error.localizedDescription)"
    }
}
