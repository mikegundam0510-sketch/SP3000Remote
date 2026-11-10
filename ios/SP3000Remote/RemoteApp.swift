import SwiftUI

@main
struct RemoteApp: App {
    @StateObject private var remote = BluetoothRemote()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RemoteView(remote: remote)
                .preferredColorScheme(.dark)
                .onChange(of: scenePhase) { phase in
                    if phase == .background { remote.enterBackground() }
                    if phase == .active { remote.enterForeground() }
                }
        }
    }
}
