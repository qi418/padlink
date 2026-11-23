# Release 签名说明

## 生成 keystore（一次性，本地执行）

```bash
keytool -genkeypair -v \
  -keystore signing/padlink.jks \
  -alias padlink \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass padlink2026 -keypass padlink2026 \
  -dname "CN=PadLink, OU=Dev, O=PadLink, L=Shenzhen, ST=Guangdong, C=CN"
```

## 约定

- 默认密码：`padlink2026`（**示例密码，正式发布请更换并妥善保管**）
- `signing/padlink.jks` 已加入 `.gitignore`，**不会入库、不随仓库分发**
- `app/build.gradle.kts` 中的 `signingConfigs` 用 `File.exists()` 判断：
  - 存在 `signing/padlink.jks` → release 使用该 keystore 签名
  - 不存在 → release 自动回退 **debug 签名**，CI / 首次构建**无需任何密钥**即可产出可安装 APK
- 正式上架前：替换为自己的密钥，并评估开启 minify
