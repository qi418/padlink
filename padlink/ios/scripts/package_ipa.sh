#!/usr/bin/env bash
# =============================================================================
# package_ipa.sh —— 从 xcodebuild 产出的未签名 xcarchive 打包出未签名 .ipa
#
# 用法:
#   scripts/package_ipa.sh <PadLinkHelper.xcarchive> [输出目录]
#   - 第 1 个参数（必填）: xcarchive 路径
#   - 第 2 个参数（可选）: ipa 输出目录，默认当前目录
#   输出: <输出目录>/PadLinkHelper-unsigned.ipa
#
# 产物供全能签 / 爱思助手等企业签工具重签后安装。
# App 无状态：证书失效重装后 30 秒内自动恢复广播。
# =============================================================================
set -euo pipefail

ARCHIVE="${1:?用法: scripts/package_ipa.sh <PadLinkHelper.xcarchive> [输出目录]}"
OUT_DIR="${2:-.}"

APP="$ARCHIVE/Products/Applications/PadLinkHelper.app"
if [ ! -d "$APP" ]; then
    echo "错误: 找不到 $APP" >&2
    echo "请确认传入的是 xcodebuild archive 产出的 .xcarchive 目录。" >&2
    exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# 1) 组装标准 ipa 目录结构: Payload/PadLinkHelper.app
mkdir -p "$WORK/Payload"
cp -R "$APP" "$WORK/Payload/"

# 2) 清理可能残留的签名目录，保证是干净未签名包（重签工具会重新写入）
rm -rf "$WORK/Payload/PadLinkHelper.app/_CodeSignature"

# 3) zip -r Payload.zip → 改名 PadLinkHelper-unsigned.ipa
(
    cd "$WORK"
    zip -qry Payload.zip Payload
)

mkdir -p "$OUT_DIR"
mv "$WORK/Payload.zip" "$OUT_DIR/PadLinkHelper-unsigned.ipa"

echo "✔ 已生成未签名包: $OUT_DIR/PadLinkHelper-unsigned.ipa"
echo "  接下来可用全能签 / 爱思助手等工具选择企业(In-House)证书重签后安装。"
