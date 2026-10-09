import Shared
import SwiftUI

@main
struct HarnessApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @State private var status = ""
    @AppStorage("pinForeground") private var pinForeground = false

    private let harness: Harness = {
        let url = Bundle.main.object(forInfoDictionaryKey: "CableURL") as? String ?? "ws://localhost:3000/cable"
        let harness = Harness(url: url)
        harness.start()
        return harness
    }()

    private let ticks = Timer.publish(every: 0.5, on: .main, in: .common).autoconnect()

    var body: some Scene {
        WindowGroup {
            VStack(alignment: .leading, spacing: 24) {
                Text(status)
                    .font(.body.monospaced())
                Toggle("Pin foreground (watchdog check)", isOn: $pinForeground)
                Spacer()
            }
            .padding()
            .onReceive(ticks) { _ in status = harness.describe() }
        }
        .onChange(of: scenePhase) { _, phase in
            harness.setForeground(value: pinForeground || phase == .active)
        }
        .onChange(of: pinForeground) { _, pinned in
            harness.setForeground(value: pinned || scenePhase == .active)
        }
    }
}
