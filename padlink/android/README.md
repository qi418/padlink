# PadLink Android（一期）

PadLink 把 **iPhone 的 ANCS 通知**（Apple Notification Center Service）通过 BLE 转发到 Android 平板：

- 平板前台服务连接 iPhone → 订阅 ANCS 通知源 → 转发为 Android 系统通知
- 支持通知动作（正向/负向按钮）回写 iPhone
- 支持「在平板打开微信/QQ」（iOS AppIdentifier → 平板本地应用映射）
- 通知中心（列表 + 详情全文）+ JSON 文件持久化（上限 200 条）

**工程名** PadLink · **包名** `com.padlink.ancs` · Kotlin 2.0.21 · AGP 8.7.3 · Gradle 8.11.1（wrapper）· compileSdk/targetSdk 35 · minSdk 26 · JDK 17

## 构建

前置条件：JDK 17（构建机需联网拉取依赖与 wrapper jar）。

```bash
# Windows
gradlew.bat assembleDebug
gradlew.bat assembleRelease

# Linux / macOS
./gradlew assembleDebug
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/{debug,release}/`

### gradle-wrapper.jar 自举说明

本工程在无 JDK 环境生成，`gradle/wrapper/gradle-wrapper.jar` 为二进制文件故未入库。首次执行 `gradlew` / `gradlew.bat` 时，脚本会自动从 Gradle 官方仓库下载 v8.11.1 的 wrapper jar：

```
https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar
```

下载完成后与标准 wrapper 行为完全一致（`gradle/wrapper/gradle-wrapper.properties` 指向 `https://services.gradle.org/distributions/gradle-8.11.1-bin.zip`）。

## 签名

见 [signing/KEYS.md](signing/KEYS.md)。默认约定：`signing/padlink.jks` 不存在时 release 自动回退 **debug 签名**，CI 无需密钥即可产出可安装 APK。

## 模块说明

代码根：`app/src/main/java/com/padlink/ancs/`

| 模块 | 职责 |
| --- | --- |
| `ble/BleCentralService` | 前台服务（`foregroundServiceType="connectedDevice"`）。扫描（仅首次配对，BluetoothLeScanner）、`createBond` 配对、按已保存地址 `connectGatt(autoConnect=true, TRANSPORT_LE)`、指数退避自动重连（5s 起 ×2，上限 60s，仅蓝牙开且屏幕亮时尝试）、`discoverServices`（失败重试 ≤3 次）、串行订阅（先 Data Source 后 Notification Source，以 `onDescriptorWrite GATT_SUCCESS` 为步进信号，CCCD 0x0001） |
| `ble/AncsSession` | Notification Source 8 字节事件解析（Removed 判定 `(byte0 & 0x02) != 0`）；Control Point 写串行队列（同一时刻仅一个在途写，`onCharacteristicWrite` 成功才出队）；断线清空队列与流式缓冲 |
| `ble/DataSourceAssembler` | Data Source 增量流式解析：`[CommandID(1B) | UID(4B) | 三元组(AttrID(1B), Len(2B 小端), 值)]`，跨 MTU 分片重组，解析完一个三元组即消费 |
| `model/NotificationStore` | 内存 LinkedHashMap（UID 去重、上限 200 条环形淘汰）+ `filesDir/notifications.json` 异步写盘 |
| `notif/NotifForwarder` | 系统通知转发：渠道「iPhone通知」，正文 = 标题 + 正文（按设置截断）；有动作时附加正/负按钮（点击回写 ANCS）；点击通知打开通知详情 |
| `launcher/PhoneLauncher` | iOS AppIdentifier → 平板本地应用：`com.tencent.xin→com.tencent.mm`、`com.tencent.mobileqq→com.tencent.mobileqq`；兜底失败 Toast 提示 |
| `ui/MainActivity` | Compose 单 Activity 四页（中文）：配对引导（扫描列表 / 系统设置配对入口 / 配对状态）、状态（蓝牙 / 连接状态 / iPhone 名 / 最近同步 / ColorOS 白名单引导）、通知中心（列表 + 详情全文 + 动作按钮 + 在平板打开微信/QQ）、设置（黑名单 / 正文最大长度 100-2000 / 自动重连开关 / 「自动连 iPhone 热点」开关默认关闭并标注未实现 / 自启动引导） |
| `prefs/Prefs` | SharedPreferences 设置（地址 / 名称 / 自动重连 / 自动热点 / 正文长度 / 黑名单） |

## 协议实现说明（对应 docs/技术规格.md §4）

- **订阅**：Android 栈 `setCharacteristicNotification(true)` + 写 CCCD `0x0001`（`ENABLE_NOTIFICATION_VALUE`），以 `onDescriptorWrite` 成功为串行步进信号。
- **GetNotificationAttributes**：每个 Added/Modified 事件向 Control Point 排队写**两条**命令：
  1. 规格标准 `[0x00, uid(4B), 0x01,0xFF,0xFF, 0x03,0xFF,0xFF]`（Title + Message，0xFFFF = 不截断）；
  2. 扩展 `[0x00, uid(4B), 0x00,0xFF,0xFF, 0x05, 0x06,0xFF,0xFF, 0x07,0xFF,0xFF]`（AppIdentifier 与 Date 为定长属性不带长度 + 正/负动作标签）——用于微信/QQ 映射与通知动作按钮。iPhone 对多属性请求兼容良好；若收到错误响应（0xA0-0xA3）仅记日志，不影响第一条的结果。
- **PerformNotificationAction**：`[0x02, uid(4B), actionId]`，actionId `0x00`=正向 / `0x01`=负向。
- **断线语义**：按 ANCS 会话语义，断线即清空通知中心与全部系统通知（UID 断线后作废）。

## 权限与运行时申请（技术规格 §5.2）

Manifest：`BLUETOOTH`(maxSdk 30)、`BLUETOOTH_SCAN`(neverForLocation)、`BLUETOOTH_CONNECT`、`ACCESS_FINE/COARSE_LOCATION`(maxSdk 30)、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE`、`POST_NOTIFICATIONS`、`WAKE_LOCK`、`RECEIVE_BOOT_COMPLETED`。

运行时（MainActivity 启动一次性申请）：API 33+ → 通知 + 扫描 + 连接；API 31-32 → 扫描 + 连接；≤30 → 定位。

## 依赖清单

全部来自 **Google Maven**（无第三方仓库）：

- androidx.core:core-ktx、androidx.appcompat:appcompat
- androidx.lifecycle:lifecycle-runtime-ktx / lifecycle-viewmodel-compose
- androidx.activity:activity-compose
- Compose BOM 2024.10.01：ui / foundation / material3、material-icons-extended

一期**不引入** Room/koin/网络等特殊库，持久化用 JSON 文件。

## 已知风险（真机验证重点）

1. **ColorOS / 国产 ROM 保活**：锁屏或清理后台会杀服务导致断连/通知延迟，需用户手动加白名单（自启动 + 电池不限），App 内置引导文案但无法程序化解决。
2. **iPhone 侧依赖系统配对**：ANCS 即 iOS「共享系统通知」，需在 iPhone「设置-蓝牙」中与平板完成系统配对；iOS 地址随机化，配对成功（BOND_BONDED）后才会保存真实地址，切勿信任扫描到的地址直接保存。
3. **通知动作 / 应用映射依赖 AppIdentifier**：iOS 对 AppIdentifier 的披露策略可能随版本变化；「在平板打开微信/QQ」还需平板已安装对应应用且 iPhone 侧通知开关全开。
4. **系统通知受 Android 通知权限与 ROM 渠道策略影响**：API 33+ 需用户授予通知权限；部分 ROM 默认折叠渠道，需用户调整。
5. **自动连 iPhone 热点为可选功能，一期未实现**：设置页开关默认关闭并禁用，仅预留。
