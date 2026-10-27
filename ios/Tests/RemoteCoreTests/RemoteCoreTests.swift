import XCTest
@testable import RemoteCore

final class RemoteCoreTests: XCTestCase {
    func testEveryCommandMatchesAndroidConsumerUsages() {
        let expected: [(RemoteCommand, UInt8, [UInt8])] = [
            (.previous, 1, [4]), (.playPause, 1, [1]), (.next, 1, [2]),
            (.volumeDown, 1, [32]), (.volumeUp, 1, [16]), (.mute, 1, [64]),
            (.screen, 4, [0x30, 0]), (.back, 4, [0x24, 0x02])
        ]
        XCTAssertEqual(expected.count, RemoteCommand.allCases.count)
        for (command, id, bytes) in expected {
            let report = HIDProfile.press(command)
            XCTAssertEqual(report.id, id)
            XCTAssertEqual(report.bytes, bytes)
            XCTAssertEqual(report.released, HIDProfile.neutral(id))
        }
    }

    func testDescriptorInputSizesAgreeWithActualGATTValues() {
        // Interpret the HID short items, rather than asserting a copy of the descriptor.
        let bytes = HIDProfile.reportMap
        var index = 0, size = 0, count = 0, id: UInt8 = 0, depth = 0
        var bits: [UInt8: Int] = [:]
        while index < bytes.count {
            let prefix = bytes[index]
            let length = [0, 1, 2, 4][Int(prefix & 3)]
            XCTAssertLessThan(index + length, bytes.count)
            guard index + length < bytes.count else { return }
            let value = (0..<length).reduce(0) { $0 | Int(bytes[index + 1 + $1]) << ($1 * 8) }
            switch prefix & 0xFC {
            case 0x74: size = value
            case 0x94: count = value
            case 0x84: id = UInt8(value)
            case 0x80: bits[id, default: 0] += size * count
            case 0xA0: depth += 1
            case 0xC0: depth -= 1
            default: break
            }
            XCTAssertGreaterThanOrEqual(depth, 0)
            index += length + 1
        }
        XCTAssertEqual(depth, 0)
        XCTAssertEqual(bits, [1: 8, 4: 16])
        for command in RemoteCommand.allCases {
            let report = HIDProfile.press(command)
            XCTAssertEqual(report.bytes.count * 8, bits[report.id])
        }
    }

    func testReleaseIsDelayedFromActualSuccessfulPress() {
        var outbox = ReportOutbox()
        outbox.enqueue(.volumeUp)
        XCTAssertEqual(outbox.next(at: 100), HIDProfile.press(.volumeUp))
        // Failed updateValue calls leave the exact same press queued, even much later.
        XCTAssertEqual(outbox.next(at: 110), HIDProfile.press(.volumeUp))
        outbox.acknowledge(at: 110)
        XCTAssertNil(outbox.next(at: 110.034))
        XCTAssertEqual(outbox.next(at: 110.036), HIDProfile.press(.volumeUp).released)
        outbox.acknowledge(at: 110.036)
        XCTAssertTrue(outbox.isEmpty)
    }

    func testRapidTapsRemainSeparateAndOrdered() {
        var outbox = ReportOutbox()
        outbox.enqueue(.playPause)
        outbox.enqueue(.playPause)
        outbox.enqueue(.back)
        var delivered: [InputReport] = []
        var time: TimeInterval = 0
        while !outbox.isEmpty {
            delivered.append(outbox.next(at: time)!)
            outbox.acknowledge(at: time)
            time = outbox.nextSendTime
        }
        XCTAssertEqual(delivered, [.init(id: 1, bytes: [1]), .init(id: 1, bytes: [0]),
                                   .init(id: 1, bytes: [1]), .init(id: 1, bytes: [0]),
                                   .init(id: 4, bytes: [0x24, 2]), .init(id: 4, bytes: [0, 0])])
    }

    func testQueueLimitNeverDropsTheReleaseHalfOfATap() {
        var outbox = ReportOutbox(maximumTaps: 1)
        XCTAssertTrue(outbox.enqueue(.volumeUp))
        outbox.acknowledge(at: 0)
        XCTAssertFalse(outbox.enqueue(.next))
        XCTAssertEqual(outbox.next(at: 1), .init(id: 1, bytes: [0]))
    }

    func testCancellationDiscardsCommandsAndReleasesEachSubscribedReport() {
        var outbox = ReportOutbox()
        outbox.enqueue(.next)
        outbox.replaceWithReleases(for: [1, 4, 255])
        XCTAssertEqual(outbox.count, 2)
        XCTAssertEqual(outbox.next(at: 0), .init(id: 1, bytes: [0]))
        outbox.acknowledge(at: 0)
        XCTAssertEqual(outbox.next(at: 1), .init(id: 4, bytes: [0, 0]))
        outbox.reset()
        XCTAssertTrue(outbox.isEmpty)
        XCTAssertNil(outbox.next(at: 100))
    }
}
