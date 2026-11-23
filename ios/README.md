# PadLinkHelper —— iPhone 连接助手（iOS）

PadLink iPhone 端助手 App。它**不读取任何通知**：只用 `CBPeripheralManager` 在前后台
持续广播自定义服务 `5E3F6A11-2C4D-4B7E-9A10-1F2E3D4C5B6A`（广播名 `PadLink-Phone`），
让用户的 Oppo 平板（BLE Central / ANCS 客户端）能稳定发现并连接 iPhone。

> 通知链路说明：ANCS 由 iOS 系统对**已配对设备**直接提供，核心通知链路不依赖本 App；
> 配对成功后即使删除本 App，平板仍能收通知（见 `../docs/技术规格.md` §3）。
> 本 App 定位为可选连接助手：保持可发现 + 状态 UI + 配对引导。

## 工程信息

| 项 | 值 |
|---|---|
| 工程名 | PadLinkHelper（`PadLinkHelper.xcodeproj`，手写 project.pbxproj，objectVersion 56） |
| Target | PadLinkHelper（单 target，App） |
| Bundle ID | `com.padlink.helper` |
| Deployment Target | iOS 15.0 |
| 语言 / UI | Swift 5 + SwiftUI，无任何第三方依赖 |
| 签名 | `CODE_SIGN_STYLE = Automatic`、`DEVELOPMENT_TEAM` 留空；CI 用 `CODE_SIGNING_ALLOWED=NO` 出未签名产物 |

## 目录结构

```
ios/
├── PadLinkHelper.xcodeproj/
│   ├── project.pbxproj
│   └── xcshareddata/xcschemes/PadLinkHelper.xcscheme   # 共享 scheme，CI 可直接 -scheme PadLinkHelper
├── PadLinkHelper/
│   ├── PadLinkHelperApp.swift   # @main；启动即创建广播器（状态恢复）；scenePhase active 时恢复广播
│   ├── ContentView.swift        # 单页中文 UI：状态卡 + 配对三步引导 + 提示卡 + 技术信息
│   ├── BleBroadcaster.swift     # CBPeripheralManager 广播 + 状态恢复 + 状态发布
│   └── Info.plist
├── scripts/
│   └── package_ipa.sh           # xcarchive → 未签名 PadLinkHelper-unsigned.ipa
└── README.md
```

## 在 macOS（CI）上构建

```bash
xcodebuild \
  -project PadLinkHelper.xcodeproj \
  -scheme PadLinkHelper \
  -configuration Release \
  -archivePath build/PadLinkHelper.xcarchive \
  archive \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGN_IDENTITY="" \
  CODE_SIGNING_REQUIRED=NO
```

说明：

- `CODE_SIGNING_ALLOWED=NO` 让 xcodebuild 完全跳过签名，产出**未签名** .app；
- 在本地 Mac 直接构建（不带这三个参数）需要配置 `DEVELOPMENT_TEAM` 与个人/企业开发者账号；
- scheme 已放在 xcshareddata（共享），CI 不会遇到"无共享 scheme"报错。

## 打包未签名 ipa（供企业签工具重签）

```bash
bash scripts/package_ipa.sh build/PadLinkHelper.xcarchive build/
# 输出: build/PadLinkHelper-unsigned.ipa
```

脚本逻辑：从 `xcarchive/Products/Applications/PadLinkHelper.app` 复制出
`Payload/PadLinkHelper.app`（并清除 `_CodeSignature` 残留）→ `zip -r Payload.zip Payload`
→ 改名 `PadLinkHelper-unsigned.ipa`。之后用全能签 / 爱思助手等工具选企业（In-House）
证书重签即可安装；App 无状态，证书失效重装后 30 秒内自动恢复广播。

## 后台广播的已知行为与限制（重要）

1. **localName 会被隐藏**：iOS 在后台广播时会隐藏/替换自定义 localName，
   平板端**不要依赖** `PadLink-Phone` 这个名字来识别 iPhone。
2. **UUID 可能进 overflow 区**：128 位服务 UUID 在后台可能被放到 scan response 的
   overflow 区。平板端按服务 UUID 过滤即可（Android 的 `ScanRecord` 会合并解析 adv 数据
   与 scan response，`getServiceUuids()` 一般能看到该 UUID）；若个别 ROM 的
   `ScanFilter.setServiceUuid` 匹配不到，请扫描后自行比对 `getServiceUuids()`，
   或先连接再 `discoverServices` 双重确认（连上后系统 ANCS 服务
   `7905F431-B5CE-4E99-A40F-4B1E122D00D0` 一定可见）。
3. **后台广播可能被暂停**：无 GATT 连接/订阅时 iOS 可能因省电暂停广播，App 回到前台自动恢复；
   平板短暂搜不到时，让用户打开本 App 一次即可。
4. **iPhone 重启后需打开一次**：iOS 不会自动启动普通 App，重启后请打开本 App 一次恢复广播。
5. **状态恢复**：`CBPeripheralManagerOptionRestoreIdentifierKey` 固定为
   `com.padlink.helper.ble.restore`；系统因蓝牙事件在后台拉起 App 时会走
   `willRestoreState` 重开广播。

## Info.plist 关键项

| Key | 值 |
|---|---|
| `UIBackgroundModes` | `bluetooth-peripheral`（后台广播必需） |
| `NSBluetoothAlwaysUsageDescription` | 中文权限说明（注明不读通知内容） |
| `UILaunchScreen` | 空 dict |
| `UIRequiredDeviceCapabilities` | `arm64` |
| `CFBundleIdentifier` | `$(PRODUCT_BUNDLE_IDENTIFIER)` = `com.padlink.helper` |
| `CFBundleDisplayName` | PadLink 助手 |

## 代码要点

- `BleBroadcaster`：单例，App 启动即创建（`PadLinkHelperApp.init`），带固定 RestoreIdentifier；
  `peripheralManagerDidUpdateState == .poweredOn` → `startAdvertising`（服务 UUID + `PadLink-Phone`）；
  `willRestoreState` 重开广播；进入后台 5 秒后复查一次状态，若 iOS 停止了广播则 state 变为
  `stopped`，UI 状态卡显示"广播异常"提示。
- 生命周期：`scenePhase == .active` → `checkAndStartAdvertising()`（检查 `isAdvertising`，停了就重开）。
- UI 单页：大号状态卡（蓝牙关闭 / 正在广播 / 广播异常 / 未授权 / 不支持等）、配对三步图文引导、
  提示卡（重启后打开一次 / 不读通知 / 证书失效 30 秒恢复）、技术信息卡。

## 首次 CI 排错

- 先跑 `xcodebuild -project PadLinkHelper.xcodeproj -list` 确认 scheme / target 可解析；
- 报签名相关错误 → 确认命令行带了 `CODE_SIGNING_ALLOWED=NO CODE_SIGN_IDENTITY="" CODE_SIGNING_REQUIRED=NO`；
- 本工程在 Windows 上手写生成、未在 Mac 上编译验证；若 pbxproj 有语法问题按报错行修复即可
  （objectVersion 56 与 Xcode 14/15 兼容）。
