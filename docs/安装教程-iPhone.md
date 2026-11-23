# PadLink iPhone 端安装教程（自签安装）

PadLinkHelper 是"连接助手"，**不读取、不处理任何通知内容**（iOS 不允许第三方 App 读其他 App 的通知；通知流转由 iOS 系统级 ANCS 提供）。它的作用是让 iPhone 在后台保持可被平板发现/连接。**即使不装它，配对后也能收通知**，但装上后连接更稳。

## 一、获取未签名 ipa

1. 仓库 → **Actions** → **Build iOS IPA (unsigned)** → Run workflow。
2. 完成后下载 Artifact **PadLinkHelper-unsigned-IPA**，解压得到 `PadLinkHelper-unsigned.ipa`。

## 二、用"全能签"类企业签工具自签安装（以你手头的工具为准）

通用流程：

1. 打开签名工具，导入 `PadLinkHelper-unsigned.ipa`。
2. 选择你的企业证书，签名。
3. 安装到 iPhone（工具通常自带在线安装；有的需要先在 iPhone 上装工具的描述文件）。
4. 首次打开前：设置 → 通用 → VPN 与设备管理 → 点击对应企业证书 → **信任**。
5. 打开 PadLinkHelper，允许蓝牙权限，看到"正在广播"即成功。

## 三、企业签失效怎么办

- 证书失效后 App 会打不开。重新签名安装即可；本 App 无任何本地状态，**重装后 30 秒内恢复全部功能**。
- 若懒得重装：可暂时不装本 App，平板的 PadLink 依旧能收通知（见《使用教程》）。

## 四、注意

- **iPhone 重启后，需要手动打开一次 PadLinkHelper**（iOS 限制，任何 App 都无法绕过）。
- 本 App 后台广播会带来约 1-3%/天 的额外耗电，属正常范围。
