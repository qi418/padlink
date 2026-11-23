import SwiftUI

/// 单页中文 UI：大号状态卡 + 配对三步引导 + 提示卡 + 技术信息
struct ContentView: View {
    @EnvironmentObject private var broadcaster: BleBroadcaster

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                header
                statusCard
                pairingGuideCard
                tipCard
                techCard
                footerNote
            }
            .padding()
        }
        .onAppear {
            broadcaster.checkAndStartAdvertising()
        }
    }

    // MARK: - 头部

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("PadLink 助手")
                .font(.largeTitle)
                .bold()
            Text("让平板稳定发现并连接你的 iPhone")
                .font(.subheadline)
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - 状态卡

    private struct StatusInfo {
        let icon: String
        let title: String
        let detail: String
        let color: Color
    }

    private func statusInfo(for state: BleBroadcaster.BroadcastState) -> StatusInfo {
        switch state {
        case .unknown:
            return StatusInfo(
                icon: "ellipsis.circle.fill",
                title: "正在初始化…",
                detail: "正在检查蓝牙状态，请稍候。",
                color: .gray
            )
        case .bluetoothOff:
            return StatusInfo(
                icon: "antenna.radiowaves.left.and.right.slash",
                title: "蓝牙已关闭",
                detail: "请在控制中心或「设置 → 蓝牙」中打开蓝牙，广播将自动开始。",
                color: .red
            )
        case .unauthorized:
            return StatusInfo(
                icon: "hand.raised.fill",
                title: "未获得蓝牙权限",
                detail: "请在「设置 → 隐私与安全性 → 蓝牙」中允许 PadLink 助手，然后重新打开本 App。",
                color: .orange
            )
        case .unsupported:
            return StatusInfo(
                icon: "exclamationmark.triangle.fill",
                title: "此设备不支持蓝牙",
                detail: "请重启手机后重试。",
                color: .red
            )
        case .advertising:
            return StatusInfo(
                icon: "dot.radiowaves.left.and.right",
                title: "正在广播",
                detail: "平板可随时发现并连接本机（PadLink-Phone）。退到后台后 iOS 可能暂停广播，回到前台会自动恢复。",
                color: .green
            )
        case .stopped:
            return StatusInfo(
                icon: "exclamationmark.circle.fill",
                title: "广播异常",
                detail: "蓝牙已开启但广播未运行（iOS 可能在后台暂停了广播）。回到前台会自动重试；若持续异常，请上滑关闭 App 后重新打开。",
                color: .orange
            )
        }
    }

    private var statusCard: some View {
        let info = statusInfo(for: broadcaster.state)
        return HStack(spacing: 14) {
            Image(systemName: info.icon)
                .font(.system(size: 40))
                .foregroundColor(info.color)
            VStack(alignment: .leading, spacing: 6) {
                Text(info.title)
                    .font(.title2)
                    .bold()
                    .foregroundColor(info.color)
                Text(info.detail)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(18)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 16).fill(info.color.opacity(0.12)))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(info.color.opacity(0.35), lineWidth: 1))
    }

    // MARK: - 配对三步引导

    private var pairingGuideCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("配对三步")
                .font(.headline)
            stepRow(
                number: 1,
                icon: "ipad",
                text: "在平板上打开 PadLink App，点击「配对」。平板将自动搜索到本机（PadLink-Phone）。"
            )
            stepRow(
                number: 2,
                icon: "iphone",
                text: "iPhone 上会弹出配对确认框，点击「配对」。"
            )
            stepRow(
                number: 3,
                icon: "bell.badge.fill",
                text: "打开 iPhone「设置 → 蓝牙」，点击平板设备右侧的 ⓘ，开启「共享系统通知」。"
            )
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color.gray.opacity(0.08)))
    }

    private func stepRow(number: Int, icon: String, text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack {
                Circle()
                    .fill(Color.accentColor)
                    .frame(width: 26, height: 26)
                Text("\(number)")
                    .font(.subheadline)
                    .bold()
                    .foregroundColor(.white)
            }
            Image(systemName: icon)
                .foregroundColor(.accentColor)
            Text(text)
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
    }

    // MARK: - 提示卡

    private var tipCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("使用提示")
                .font(.headline)
            tipRow(
                icon: "arrow.clockwise.circle.fill",
                text: "iPhone 重启后请打开本 App 一次，广播即自动恢复。"
            )
            tipRow(
                icon: "eye.slash.fill",
                text: "本 App 不读取任何通知内容。通知由 iOS 系统 ANCS 通道直接发送给已配对的平板，与本 App 无关。"
            )
            tipRow(
                icon: "clock.arrow.circlepath",
                text: "企业签证书失效重装后，约 30 秒内自动恢复广播，无需任何配置。"
            )
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color.gray.opacity(0.08)))
    }

    private func tipRow(icon: String, text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .foregroundColor(.orange)
            Text(text)
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
    }

    // MARK: - 技术信息卡

    private var techCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("技术信息")
                .font(.headline)
            techRow(label: "服务 UUID", value: "5E3F6A11-2C4D-4B7E-9A10-1F2E3D4C5B6A")
            techRow(label: "广播名称", value: "PadLink-Phone")
            techRow(label: "后台模式", value: "bluetooth-peripheral（支持状态恢复）")
            techRow(label: "数据存储", value: "无状态，不保存任何数据")
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color.gray.opacity(0.08)))
    }

    private func techRow(label: String, value: String) -> some View {
        HStack(alignment: .top) {
            Text(label)
                .font(.caption)
                .foregroundColor(.secondary)
                .frame(width: 70, alignment: .leading)
            Text(value)
                .font(.caption.monospaced())
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
    }

    // MARK: - 底部说明

    private var footerNote: some View {
        Text("本 App 仅负责让 iPhone 在后台保持可被发现、可连接；通知流转由 iOS 系统 ANCS 通道提供，配对成功后即使删除本 App，平板仍能收到通知。")
            .font(.caption2)
            .foregroundColor(.secondary)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 4)
    }
}
