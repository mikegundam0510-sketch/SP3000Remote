import Foundation

/// Serializes complete press/release pairs, including when CoreBluetooth applies backpressure.
/// Times are monotonic process-uptime values. A failed notification must NOT be acknowledged.
public struct ReportOutbox {
    private var reports: [InputReport] = []
    public private(set) var nextSendTime: TimeInterval = 0
    public let interval: TimeInterval
    public let maximumTaps: Int

    public init(interval: TimeInterval = 0.035, maximumTaps: Int = 8) {
        precondition(interval > 0 && maximumTaps > 0)
        self.interval = interval
        self.maximumTaps = maximumTaps
    }

    public var count: Int { reports.count }
    public var isEmpty: Bool { reports.isEmpty }

    @discardableResult
    public mutating func enqueue(_ command: RemoteCommand) -> Bool {
        guard reports.count + 2 <= maximumTaps * 2 else { return false }
        let press = HIDProfile.press(command)
        reports += [press, press.released]
        return true
    }

    public func next(at time: TimeInterval) -> InputReport? {
        time >= nextSendTime ? reports.first : nil
    }

    public mutating func acknowledge(at time: TimeInterval) {
        precondition(!reports.isEmpty && time >= nextSendTime)
        reports.removeFirst()
        nextSendTime = time + interval
    }

    public mutating func replaceWithReleases(for ids: Set<UInt8>) {
        reports = ids.sorted().compactMap(HIDProfile.neutral)
        nextSendTime = 0
    }

    public mutating func reset() {
        reports.removeAll()
        nextSendTime = 0
    }
}
