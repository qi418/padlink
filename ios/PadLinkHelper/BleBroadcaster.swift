import Foundation
import CoreBluetooth
import Combine

/// 蓝牙广播器：让 iPhone 在前/后台持续广播自定义服务，
/// 便于 Oppo 平板（BLE Central）稳定发现并连接本机。
///
/// 注意：本 App 不读取任何通知；ANCS 通知链路由 iOS 系统直接提供，
/// 与本 App 的功能逻辑无关。本 App 只负责让 iPhone 保持"可被发现、可连接"。
final class BleBroadcaster: NSObject, ObservableObject, CBPeripheralManagerDelegate {

    /// 自定义服务 UUID（平板端按此 UUID 过滤扫描结果）
    static let serviceUUID = CBUUID(string: "5E3F6A11-2C4D-4B7E-9A10-1F2E3D4C5B6A")
    /// 广播名称。iOS 在后台广播时会隐藏 localName，平板端不要依赖它。
    static let localName = "PadLink-Phone"
    /// 状态恢复标识（固定字符串）。系统在后台重启 App 时据此恢复广播。
    static let restoreIdentifier = "com.padlink.helper.ble.restore"

    /// 广播状态（供 SwiftUI 状态卡展示）
    enum BroadcastState: Equatable {
        case unknown        // 初始化中
        case bluetoothOff   // 蓝牙已关闭
        case unauthorized   // 未获得蓝牙权限
        case unsupported    // 设备不支持蓝牙
        case advertising    // 正在广播
        case stopped        // 蓝牙已开但广播未运行（异常 / 被系统在后台暂停）
    }

    @Published private(set) var state: BroadcastState = .unknown
    @Published private(set) var isAdvertising = false
    @Published private(set) var didRestore = false

    /// 单例：App 启动即创建，保证状态恢复尽早生效
    static let shared = BleBroadcaster()

    private var peripheralManager: CBPeripheralManager?

    override init() {
        super.init()
        peripheralManager = CBPeripheralManager(
            delegate: self,
            queue: nil,
            options: [CBPeripheralManagerOptionRestoreIdentifierKey: Self.restoreIdentifier]
        )
    }

    // MARK: - 对外控制

    /// 回到前台（scenePhase == active）/页面出现时调用：检查并恢复广播
    func checkAndStartAdvertising() {
        guard let pm = peripheralManager, pm.state == .poweredOn else { return }
        if !pm.isAdvertising {
            startAdvertising(on: pm)
        }
        refreshState()
    }

    /// 进入后台时调用：iOS 可能随后停止广播，延迟复查状态供 UI 提示
    func notifySceneBackground() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
            self?.refreshState()
        }
    }

    /// 回到前台时调用
    func notifySceneActive() {
        checkAndStartAdvertising()
    }

    // MARK: - 内部实现

    private func startAdvertising(on pm: CBPeripheralManager) {
        let advertisement: [String: Any] = [
            CBAdvertisementDataServiceUUIDsKey: [Self.serviceUUID],
            CBAdvertisementDataLocalNameKey: Self.localName,
        ]
        pm.startAdvertising(advertisement)
    }

    private func refreshState() {
        guard let pm = peripheralManager else {
            state = .unknown
            return
        }
        isAdvertising = pm.isAdvertising
        switch pm.state {
        case .poweredOn:
            state = pm.isAdvertising ? .advertising : .stopped
        case .poweredOff:
            state = .bluetoothOff
        case .unauthorized:
            state = .unauthorized
        case .unsupported:
            state = .unsupported
        case .unknown, .resetting:
            state = .unknown
        @unknown default:
            state = .unknown
        }
    }

    // MARK: - CBPeripheralManagerDelegate

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if peripheral.state == .poweredOn {
            startAdvertising(on: peripheral)
        }
        refreshState()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String: Any]) {
        // 系统在后台重启了本 App（例如因蓝牙事件）：
        // 标记恢复并立即尝试重开广播；didUpdateState(.poweredOn) 随后也会回调，双保险。
        didRestore = true
        if peripheral.state == .poweredOn {
            startAdvertising(on: peripheral)
        }
        refreshState()
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error = error {
            NSLog("PadLinkHelper: 广播启动失败 \(error.localizedDescription)")
        }
        refreshState()
    }
}
