#!/bin/bash
#
# 快捷生成 Android Release APK（自动签名）
#
# 签名信息存放在仓库根目录的 local.properties（该文件不入库），键名与
# RushRush/D1 项目保持一致，便于在两个仓库之间沿用同一套习惯：
#
#   STORE_FILE=documents/todoapp-release.jks   # 绝对路径，或相对仓库根目录
#   STORE_PASSWORD=********
#   KEY_ALIAS=todoapp
#   KEY_PASSWORD=********
#
# 首次运行时若密钥库不存在，脚本会用 keytool 自动生成，并把随机密码写回
# local.properties。请备份 documents/ 下的 .jks 与 local.properties：
# 密钥库一旦丢失，已安装的旧版本将无法覆盖升级。
#
# 用法:
#   scripts/build_release_apk.sh             # 增量构建并签名
#   scripts/build_release_apk.sh --clean     # 先 clean 再全量构建
#   scripts/build_release_apk.sh --help
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
LOCAL_PROPERTIES="$PROJECT_DIR/local.properties"

APP_MODULE="androidApp"
APK_DIR="$PROJECT_DIR/$APP_MODULE/build/outputs/apk/release"
DEFAULT_STORE_REL="documents/todoapp-release.jks"
DEFAULT_ALIAS="todoapp"

usage() {
    awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"
    exit 0
}

# ---------- 参数 ----------
CLEAN_BUILD=0
for arg in "$@"; do
    case "$arg" in
        -h|--help) usage ;;
        --clean) CLEAN_BUILD=1 ;;
        *)
            echo "未知参数: ${arg}（用 --help 查看用法）" >&2
            exit 2
            ;;
    esac
done

# ---------- 读取 / 写入 local.properties ----------
read_prop() {
    local key="$1"
    [ -f "$LOCAL_PROPERTIES" ] || return 0
    sed -n "s/^[[:space:]]*${key}[[:space:]]*=[[:space:]]*//p" "$LOCAL_PROPERTIES" | tail -n 1
}

# 就地更新或追加一个键，保留文件里其它内容（含 sdk.dir）
upsert_prop() {
    local key="$1" value="$2" tmp
    tmp="$(mktemp)"
    if [ -f "$LOCAL_PROPERTIES" ] && grep -qE "^[[:space:]]*${key}[[:space:]]*=" "$LOCAL_PROPERTIES"; then
        awk -v k="$key" -v v="$value" '
            $0 ~ "^[[:space:]]*" k "[[:space:]]*=" { print k "=" v; next }
            { print }
        ' "$LOCAL_PROPERTIES" > "$tmp"
    else
        if [ -f "$LOCAL_PROPERTIES" ]; then
            cat "$LOCAL_PROPERTIES" > "$tmp"
        fi
        printf '%s=%s\n' "$key" "$value" >> "$tmp"
    fi
    mv "$tmp" "$LOCAL_PROPERTIES"
}

# ---------- JDK ----------
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
    JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
    echo "未找到 JDK，请先安装 JDK 17+ 或设置 JAVA_HOME" >&2
    exit 1
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

# ---------- 签名配置 ----------
STORE_FILE="$(read_prop STORE_FILE)"
STORE_PASSWORD="$(read_prop STORE_PASSWORD)"
KEY_ALIAS="$(read_prop KEY_ALIAS)"
KEY_PASSWORD="$(read_prop KEY_PASSWORD)"

[ -n "$STORE_FILE" ] || STORE_FILE="$DEFAULT_STORE_REL"
[ -n "$KEY_ALIAS" ] || KEY_ALIAS="$DEFAULT_ALIAS"

# 相对路径按仓库根目录解析（绝对路径原样使用）
case "$STORE_FILE" in
    /*) STORE_FILE_ABS="$STORE_FILE" ;;
    *)  STORE_FILE_ABS="$PROJECT_DIR/$STORE_FILE" ;;
esac

if [ -f "$STORE_FILE_ABS" ]; then
    # 已有密钥库却没有口令记录时，不能凭空生成新口令（与密钥库不匹配），直接报错
    if [ -z "$STORE_PASSWORD" ] || [ -z "$KEY_PASSWORD" ]; then
        echo "密钥库已存在，但 ${LOCAL_PROPERTIES} 中没有对应的口令记录：" >&2
        echo "  $STORE_FILE_ABS" >&2
        echo "请补齐 STORE_PASSWORD / KEY_PASSWORD（PKCS12 要求两者一致），" >&2
        echo "或删除该密钥库让脚本重新生成一份。" >&2
        exit 1
    fi
else
    # 首次运行：生成口令（PKCS12 要求 store 与 key 口令一致）
    STORE_PASSWORD="${STORE_PASSWORD:-$(openssl rand -hex 16)}"
    KEY_PASSWORD="$STORE_PASSWORD"
    upsert_prop STORE_PASSWORD "$STORE_PASSWORD"
    upsert_prop KEY_PASSWORD "$KEY_PASSWORD"
fi

echo "=========================================="
echo "构建 Release APK"
echo "项目目录: $PROJECT_DIR"
echo "JAVA_HOME: $JAVA_HOME"
echo "密钥库:   $STORE_FILE_ABS"
echo "别名:     $KEY_ALIAS"
echo "=========================================="

# 首次运行：生成密钥库并记下路径
if [ ! -f "$STORE_FILE_ABS" ]; then
    echo "密钥库不存在，正在生成..."
    mkdir -p "$(dirname "$STORE_FILE_ABS")"
    keytool -genkeypair \
        -keystore "$STORE_FILE_ABS" \
        -storetype PKCS12 \
        -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$STORE_PASSWORD" -keypass "$KEY_PASSWORD" \
        -dname "CN=TodoApp, O=TodoApp, C=CN"
    upsert_prop STORE_FILE "$STORE_FILE"
    upsert_prop KEY_ALIAS "$KEY_ALIAS"
    echo "已生成密钥库，签名密码记录在 ${LOCAL_PROPERTIES}（请勿入库，务必备份）"
    echo "=========================================="
fi

# ---------- 构建 ----------
cd "$PROJECT_DIR"

GRADLE_TASKS=(":${APP_MODULE}:assembleRelease")
[ "$CLEAN_BUILD" -eq 1 ] && GRADLE_TASKS=("clean" "${GRADLE_TASKS[@]}")

# 通过 AGP 的 injected signing 属性签名，无需改动 build.gradle.kts
./gradlew "${GRADLE_TASKS[@]}" \
    --build-cache \
    --parallel \
    --daemon \
    --warning-mode all \
    "-Pandroid.injected.signing.store.file=$STORE_FILE_ABS" \
    "-Pandroid.injected.signing.store.password=$STORE_PASSWORD" \
    "-Pandroid.injected.signing.key.alias=$KEY_ALIAS" \
    "-Pandroid.injected.signing.key.password=$KEY_PASSWORD"

# ---------- 结果 ----------
# 签名成功后产物为 androidApp-release.apk，未签名时是 -unsigned.apk
APK_FILE="$(ls -t "$APK_DIR"/*.apk 2>/dev/null | grep -v -- '-unsigned\.apk$' | head -n 1 || true)"
[ -n "$APK_FILE" ] || APK_FILE="$(ls -t "$APK_DIR"/*.apk 2>/dev/null | head -n 1 || true)"

if [ -z "$APK_FILE" ]; then
    echo "=========================================="
    echo "构建失败：未找到 APK 产物"
    echo "=========================================="
    exit 1
fi

echo "=========================================="
echo "构建成功!"
echo "APK: $APK_FILE"
echo "大小: $(du -h "$APK_FILE" | cut -f1)"

VERSION_LINE="$(sed -n 's/.*"versionName"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
    "$APK_DIR/output-metadata.json" 2>/dev/null | head -n 1 || true)"
[ -n "$VERSION_LINE" ] && echo "版本: $VERSION_LINE"

# 校验签名
SDK_DIR="$(read_prop sdk.dir)"
[ -n "$SDK_DIR" ] || SDK_DIR="$HOME/Library/Android/sdk"
APKSIGNER="$(ls -t "$SDK_DIR"/build-tools/*/apksigner 2>/dev/null | head -n 1 || true)"

if [ -z "$APKSIGNER" ]; then
    echo "签名: 未找到 apksigner，跳过校验"
elif "$APKSIGNER" verify "$APK_FILE" >/dev/null 2>&1; then
    echo "签名: 有效（$(basename "$(dirname "$APKSIGNER")") 校验通过）"
    echo ""
    echo "安装到已连接设备: adb install -r \"$APK_FILE\""
else
    echo "签名: 校验未通过，产物为未签名 APK"
    echo "=========================================="
    exit 1
fi
echo "=========================================="
