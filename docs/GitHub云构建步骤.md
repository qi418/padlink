# GitHub 云构建步骤（拿到 APK / ipa 下载链接）

PadLink 的安装包不是预先打包好的文件，而是在**你自己的 GitHub 仓库**里由 GitHub Actions 云编译生成。全部操作用浏览器完成，不需要安装 git、不需要命令行。

---

## 第一步：注册 / 登录 GitHub

1. 浏览器打开 <https://github.com>。
2. 没有账号：右上角 **Sign up** → 填邮箱、密码、用户名 → 验证邮箱，全程约 2 分钟。
3. 有账号：直接 **Sign in**。

## 第二步：创建仓库

1. 右上角 **"+" 图标 → New repository**。
2. Repository name 填 `padlink`。
3. 可见性选 **Public**（免费账号用 macOS 云构建必须公开；Private 仓库免费额度用不了 iOS 流水线）。
4. **不要勾选** "Add a README file"（空仓库）。
5. 点 **Create repository**。

## 第三步：上传代码（网页拖拽，无需 git）

1. 创建成功后页面会显示 "Quick setup"，点 **"uploading an existing file"** 链接。
2. 打开文件资源管理器，进入：
   `D:\Users\34399\Documents\deepseek-harness\default-workspace\padlink`
3. 把里面的**全部内容**（android、ios、docs、.github、README.md 等）一起**拖进网页上传区**。
4. 等待全部文件变绿勾，点 **Commit changes**。
5. **上传后必检**：在仓库文件列表确认能看到 `.github` 文件夹（以及 `android`、`ios`、`docs`）。浏览器有时会漏传点号开头的隐藏文件夹；若看不到 `.github`，回到 "Add file → Upload files" 把 `.github` 文件夹单独再拖一次（可在资源管理器地址栏输入 `D:\Users\34399\Documents\deepseek-harness\default-workspace\padlink\.github` 打开它）。

> 若跳过此检查导致 Actions 页面提示 "Workflow doesn't exist"，就是 `.github` 没传上去，补传后即恢复。

> 备选（会用 git 的话）：在 padlink 目录执行
> `git init && git add . && git commit -m "init" && git branch -M main && git remote add origin https://github.com/你的用户名/padlink.git && git push -u origin main`

## 第四步：触发两条构建流水线

进入仓库页面，点顶部 **Actions** 标签（首次上传后才会出现该标签）。

### A. 构建平板 APK
1. 左侧列表点 **Build Android APK**。
2. 右侧 **Run workflow** 下拉框 → 保持 main 分支 → 点绿色 **Run workflow** 按钮。
3. 刷新页面出现黄色圆点运行中；首次约 4-6 分钟（下载 Gradle + 依赖）。

### B. 构建 iPhone ipa
1. 左侧列表点 **Build iOS IPA (unsigned)**。
2. 同样 **Run workflow** → 绿色按钮。macOS 机器首次约 3-5 分钟。

两条流水线可以同时跑，互不影响。

## 第五步：下载安装包

1. 在 Actions 列表点进**已完成的运行**（绿色对勾）。
2. 拉到页面最底部 **Artifacts** 区域：
   - `PadLink-APK` → 下载 zip → 解压出 `app-release.apk` → 传到平板安装。
   - `PadLinkHelper-unsigned-IPA` → 下载 zip → 解压出 `PadLinkHelper-unsigned.ipa` → 用全能签/爱思助手选企业证书重签后装到 iPhone。

---

## 常见问题

| 问题 | 处理 |
|---|---|
| 没有 Actions 标签 | 先完成第三步上传并 Commit，标签会自动出现 |
| Run workflow 按钮点不了 | 先点一次左侧流水线名称进入页面；Actions 需在仓库 Settings → Actions → General 允许（新仓库默认允许） |
| 流水线跑了但失败 | 点进运行 → 点失败的红叉步骤 → 看日志；按《排障指南.md》第三部分处理 |
| 构建产物会过期吗 | Artifacts 默认保留 90 天，过期重跑一次即可 |
| macOS 构建收费吗 | 公共仓库免费额度足够日常使用 |
| 更新代码后怎么拿新版 | 重新上传改动文件（或 git push）→ 重复第四、五步 |

---

## 备选方案：平板 APK 本地构建（不想用 GitHub 时）

前提：这台 Windows 电脑网络正常，并安装 [Android Studio](https://developer.android.com/studio)（自带 JDK）。

```
cd D:\Users\34399\Documents\deepseek-harness\default-workspace\padlink\android
gradlew.bat assembleDebug
```

产物：`app\build\outputs\apk\debug\app-debug.apk`。

> 注意：**iPhone 的 ipa 无法在 Windows 上编译**（需要 macOS + Xcode）。没有 Mac 时，GitHub 云构建是唯一路径。
