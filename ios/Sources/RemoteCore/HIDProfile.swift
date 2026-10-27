import Foundation

public enum RemoteCommand: String, CaseIterable {
    case previous, playPause, next, volumeDown, volumeUp, mute, screen, back
}

public struct InputReport: Equatable {
    public let id: UInt8
    public let bytes: [UInt8]

    public init(id: UInt8, bytes: [UInt8]) {
        self.id = id
        self.bytes = bytes
    }

    public var released: InputReport {
        InputReport(id: id, bytes: Array(repeating: 0, count: bytes.count))
    }
}

public enum HIDProfile {
    public static let mediaReportID: UInt8 = 1
    public static let extendedReportID: UInt8 = 4
    public static let name = "DAP Remote"

    // The Android app's Consumer Control collections, with their original report IDs.
    // GATT Report Reference descriptors identify reports; notification values omit the ID.
    public static let reportMap: [UInt8] = [
        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, mediaReportID,
        0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x07,
        0x09, 0xCD, 0x09, 0xB5, 0x09, 0xB6, 0x09, 0xB7,
        0x09, 0xE9, 0x09, 0xEA, 0x09, 0xE2,
        0x81, 0x02, 0x75, 0x01, 0x95, 0x01, 0x81, 0x03, 0xC0,
        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, extendedReportID,
        0x15, 0x00, 0x26, 0xFF, 0x0F, 0x19, 0x00, 0x2A, 0xFF, 0x0F,
        0x75, 0x10, 0x95, 0x01, 0x81, 0x00, 0xC0
    ]

    public static func press(_ command: RemoteCommand) -> InputReport {
        switch command {
        case .playPause: return InputReport(id: mediaReportID, bytes: [0x01])
        case .next: return InputReport(id: mediaReportID, bytes: [0x02])
        case .previous: return InputReport(id: mediaReportID, bytes: [0x04])
        case .volumeUp: return InputReport(id: mediaReportID, bytes: [0x10])
        case .volumeDown: return InputReport(id: mediaReportID, bytes: [0x20])
        case .mute: return InputReport(id: mediaReportID, bytes: [0x40])
        case .screen: return InputReport(id: extendedReportID, bytes: [0x30, 0x00])
        case .back: return InputReport(id: extendedReportID, bytes: [0x24, 0x02])
        }
    }

    public static func neutral(_ id: UInt8) -> InputReport? {
        switch id {
        case mediaReportID: return InputReport(id: id, bytes: [0])
        case extendedReportID: return InputReport(id: id, bytes: [0, 0])
        default: return nil
        }
    }
}
