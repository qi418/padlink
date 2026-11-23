import SwiftUI

@main
struct PadLinkHelperApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var broadcaster: BleBroadcaster

    init() {
        // App 一启动就创建广播器（带固定 RestoreIdentifier）。
        // 系统因蓝牙事件在后台拉起本 App 时，能及时走 willRestoreState 恢复广播。
        _broadcaster = StateObject(wrappedValue: BleBroadcaster.shared)
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(broadcaster)
        }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .active:
                // 回到前台：检查广播状态，若 iOS 在后台停止了广播则立刻恢复
                broadcaster.notifySceneActive()
            case .background:
                // 进入后台：稍后复查状态；若 iOS 停止了广播，state 会变为 stopped 供 UI 提示
                broadcaster.notifySceneBackground()
            default:
                break
            }
        }
    }
}
