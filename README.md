# PadLink — iPhone 通知实时流转到 Oppo Pad 4 Pro

双端 App + 云编译：平板端 `PadLink`（Android，ANCS 客户端 + 通知中心），iPhone 端 `PadLinkHelper`（可选连接助手）。核心链路走苹果官方 **ANCS**（Apple Notification Center Service），无需越狱、无需 Mac、无需公网服务器。

## 目录结构

```
padlink/
├── docs/
│   ├── 技术规格.md          # 架构与 ANCS 协议实现依据（开发文档）
│   ├── 安装教程-平板.md
│   ├── 安装教程-iPhone.md
│   ├── 使用教程.md          # 含真机验收清单
│   ├── GitHub云构建步骤.md  # 拿到 APK/ipa 下载链接的 5 步图文
│   └── 排障指南.md          # 现象→原因→处理速查
├── android/                  # 平板端工程（Kotlin / Compose / minSdk 26）
├── ios/                      # iPhone 助手工程（SwiftUI / iOS 15+）
└── .github/workflows/        # 云编译：android.yml 出 APK，ios.yml 出未签名 ipa
```

## 快速开始（对用户）

1. 把整个 `padlink/` 目录推送到你的 GitHub 仓库（网页拖拽即可，详见《GitHub云构建步骤.md》）。
2. Actions 手动触发两个 workflow，下载 **PadLink-APK** 与 **PadLinkHelper-unsigned-IPA**。
3. 平板装 APK → 按《安装教程-平板.md》配权限。
4. iPhone 用全能签自签 ipa → 按《安装教程-iPhone.md》安装。
5. 按《使用教程.md》配对、打开"共享系统通知"、验证。
6. 卡住时查《排障指南.md》，或把 adb 日志（排障指南第 9 条）发给我。

## 工作原理（30 秒版）

- iPhone 的 iOS 系统内置 ANCS（智能手表同款通知服务），对**已配对设备**开放：标题、正文、动作标签。
- 平板 PadLink 扮演蓝牙中央，主动连接 iPhone，订阅通知流，收到后显示在通知中心并转发安卓通知栏。
- 点击通知在平板本地打开微信/QQ 回复（平板同账号登录）。
- iPhone 助手 App 只负责让手机在后台保持可连接，不经手任何通知内容。

## 诚实声明（必须知道）

- 无法转发图片附件；无法替 iPhone 批量已读；无法远程打开 iPhone App。
- 企业签失效需重装（App 无状态，30 秒恢复）；iPhone 重启后助手 App 需手动打开一次。
- 后台常驻依赖 ColorOS 白名单配置（教程已给全步骤）。
