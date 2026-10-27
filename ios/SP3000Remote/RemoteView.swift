import RemoteCore
import SwiftUI
import UIKit

extension RemoteCommand {
    var title: String {
        switch self {
        case .previous: return "上一首"
        case .playPause: return "播放／暫停"
        case .next: return "下一首"
        case .volumeDown: return "降低音量"
        case .volumeUp: return "提高音量"
        case .mute: return "靜音"
        case .screen: return "螢幕"
        case .back: return "返回"
        }
    }

    var symbol: String {
        switch self {
        case .previous: return "backward.end.fill"
        case .playPause: return "playpause.fill"
        case .next: return "forward.end.fill"
        case .volumeDown: return "minus"
        case .volumeUp: return "plus"
        case .mute: return "speaker.slash.fill"
        case .screen: return "power"
        case .back: return "arrow.uturn.backward"
        }
    }
}

struct RemoteView: View {
    @ObservedObject var remote: BluetoothRemote
    @State private var showHelp = false
    private let silver = Color(red: 0.78, green: 0.83, blue: 0.88)

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                HStack {
                    Text("SP3000").font(.caption.weight(.semibold)).tracking(3)
                    Spacer()
                    Button { showHelp = true } label: {
                        Image(systemName: "questionmark.circle").font(.title2)
                    }
                    .accessibilityLabel("配對及安裝說明")
                }
                .foregroundStyle(silver)

                PlayerSilhouette()
                    .frame(width: 110, height: 190)
                    .accessibilityHidden(true)

                VStack(spacing: 10) {
                    Text("DAP Remote").font(.largeTitle.bold())
                    Label("藍牙測試版", systemImage: "antenna.radiowaves.left.and.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(silver)
                    Text(remote.status)
                        .font(.subheadline)
                        .multilineTextAlignment(.center)
                        .foregroundStyle(remote.canSend ? Color.green : silver)
                        .accessibilityAddTraits(.updatesFrequently)
                }

                Button {
                    if remote.isRunning { remote.stop() } else { remote.start() }
                } label: {
                    Text(remote.isRunning ? "停止連線" : "開始配對")
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .background(.white.opacity(0.12), in: RoundedRectangle(cornerRadius: 16))
                }
                .buttonStyle(.plain)

                VStack(spacing: 14) {
                    HStack(spacing: 14) {
                        key(.previous)
                        key(.playPause, prominent: true)
                        key(.next)
                    }
                    HStack(spacing: 14) {
                        key(.volumeDown)
                        key(.mute)
                        key(.volumeUp)
                    }
                }

                DisclosureGroup("額外按鍵") {
                    HStack(spacing: 14) { key(.screen, extended: true); key(.back, extended: true) }
                        .padding(.top, 12)
                    Text("螢幕及返回按鍵視乎 SP3000 韌體支援。")
                        .font(.footnote).foregroundStyle(.secondary)
                        .padding(.top, 8)
                }
                .tint(silver)

                if !remote.lastAction.isEmpty {
                    Text(remote.lastAction).font(.footnote).foregroundStyle(silver)
                }
                Text("首次使用需驗證與 SP3000 相容。請保持此 App 開啟，並在 SP3000 播放本機歌曲。")
                    .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)

                if !remote.diagnostic.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("連線診斷").font(.headline)
                        Text("iPhone 未接受遙控服務或配對未能完成；目前尚未建立可用連線。")
                        Text(remote.diagnostic).textSelection(.enabled)
                        Button("開啟 iPhone 設定") {
                            if let url = URL(string: UIApplication.openSettingsURLString) {
                                UIApplication.shared.open(url)
                            }
                        }
                    }
                    .font(.footnote).frame(maxWidth: .infinity, alignment: .leading)
                    .padding().background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 16))
                }
            }
            .padding(24)
            .frame(maxWidth: 500)
            .frame(maxWidth: .infinity)
        }
        .background(Color(red: 0.025, green: 0.035, blue: 0.045))
        .sheet(isPresented: $showHelp) {
            NavigationStack {
                List {
                    Section("直接藍牙配對") {
                        Text("1. 開啟 iPhone 及 SP3000 藍牙，按「開始配對」。")
                        Text("2. 在 SP3000 的藍牙設定搜尋「DAP Remote」並配對。")
                        Text("3. 在 SP3000 播放本機歌曲，測試播放、切歌及音量。保持 BT Sink 關閉。")
                        Text("4. 請保持 App 在前景；返回 App 後會重新開放配對。")
                    }
                    Section("相容性") {
                        Text("此版本使用 BLE 遙控，與 Android 版的傳統藍牙連線方式不同。尚未完成 iPhone 與 SP3000 實機驗證。")
                        Text("搜尋不到、配對失敗或按鍵無反應，都可能是系統或接收端未支援此連線方式。")
                        Text("連線成功表示可傳送指令；App 無法讀取 SP3000 的歌曲、播放狀態或實際音量。")
                    }
                    Section("安裝") {
                        Text("iPhone 安裝需要有效簽署。免費 Apple ID 側載通常每 7 日需要更新簽署；TestFlight 需要開發者帳戶。")
                    }
                }
                .navigationTitle("使用說明")
                .toolbar { ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { showHelp = false }
                } }
            }
        }
    }

    private func key(_ command: RemoteCommand, prominent: Bool = false, extended: Bool = false) -> some View {
        Button { remote.send(command) } label: {
            VStack(spacing: 10) {
                Image(systemName: command.symbol).font(.system(size: prominent ? 31 : 25, weight: .semibold))
                Text(command.title).font(.caption)
            }
            .frame(maxWidth: .infinity, minHeight: 90)
            .background(.white.opacity(prominent ? 0.17 : 0.07), in: RoundedRectangle(cornerRadius: 20))
            .overlay(RoundedRectangle(cornerRadius: 20).strokeBorder(.white.opacity(0.08)))
        }
        .buttonStyle(.plain)
        .disabled(extended ? !remote.canSendExtended : !remote.canSend)
        .opacity((extended ? remote.canSendExtended : remote.canSend) ? 1 : 0.35)
        .accessibilityLabel(command.title)
    }
}

private struct PlayerSilhouette: View {
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 8)
                .fill(LinearGradient(colors: [Color(white: 0.18), .black], startPoint: .topLeading, endPoint: .bottomTrailing))
                .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(Color(white: 0.65), lineWidth: 1))
            RoundedRectangle(cornerRadius: 3)
                .fill(Color(red: 0.05, green: 0.07, blue: 0.08))
                .padding(10).padding(.bottom, 35)
            VStack(spacing: 6) {
                Image(systemName: "waveform").font(.largeTitle).foregroundStyle(.white.opacity(0.6))
                Text("A&ultima").font(.system(size: 9)).tracking(1)
            }
            Circle().fill(Color(white: 0.3)).frame(width: 16, height: 25).offset(x: 59, y: -25)
        }
    }
}
