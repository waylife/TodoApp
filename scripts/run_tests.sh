#!/bin/bash
#
# 一键跑测试（TDD 日常入口）
#
# 默认跑 JVM 上的全部单测与集成测试（commonTest + desktopTest），耗时最短，
# 是「红-绿-重构」循环里最常用的命令；测试报告在
# composeApp/build/reports/tests/desktopTest/index.html。
#
# 用法:
#   scripts/run_tests.sh                        # 全量（JVM）
#   scripts/run_tests.sh --filter SyncMerge     # 只跑名字匹配的测试类（可多次指定）
#   scripts/run_tests.sh --filter "SyncMerge 本地新增"
#   scripts/run_tests.sh --rerun                # 忽略 up-to-date 缓存强制重跑
#   scripts/run_tests.sh --ios                  # 追加 iOS 模拟器测试（需 Xcode，较慢）
#   scripts/run_tests.sh --help
#
# --filter 的值原样包上 *...* 通配传给 Gradle --tests，支持类名或「类名 用例名」片段。
# 其余无法识别的参数会原样透传给 gradlew（例如 --info、-Pxxx=yyy）。
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

usage() {
    awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"
    exit 0
}

# ---------- 参数 ----------
FILTERS=()
RERUN=0
RUN_IOS=0
GRADLE_EXTRA=()
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) usage ;;
        --rerun) RERUN=1; shift ;;
        --ios) RUN_IOS=1; shift ;;
        --filter|-t)
            [ $# -ge 2 ] || { echo "--filter 需要一个参数" >&2; exit 2; }
            FILTERS+=("$2"); shift 2 ;;
        *) GRADLE_EXTRA+=("$1"); shift ;;
    esac
done

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

# ---------- Android SDK（首次运行自动写入 local.properties） ----------
if [ ! -f "$PROJECT_DIR/local.properties" ] && [ "$(uname)" = "Darwin" ] && [ -d "$HOME/Library/Android/sdk" ]; then
    echo "sdk.dir=$HOME/Library/Android/sdk" > "$PROJECT_DIR/local.properties"
    echo "已创建 local.properties（sdk.dir=$HOME/Library/Android/sdk）"
fi

# ---------- 组装任务与过滤 ----------
BASE_TASKS=(":composeApp:desktopTest")
if [ "$RUN_IOS" -eq 1 ]; then
    BASE_TASKS+=(":composeApp:iosSimulatorArm64Test")
fi

TASKS=()
for t in "${BASE_TASKS[@]}"; do
    TASKS+=("$t")
    [ "$RERUN" -eq 1 ] && TASKS+=("--rerun")
done

TEST_ARGS=()
for f in ${FILTERS[@]+"${FILTERS[@]}"}; do
    TEST_ARGS+=("--tests" "*$f*")
done

# ---------- 跑测试 ----------
cd "$PROJECT_DIR"
echo "=========================================="
echo "运行测试: ${TASKS[*]}"
[ ${#FILTERS[@]} -gt 0 ] && echo "过滤:     ${FILTERS[*]}"
echo "JAVA_HOME: $JAVA_HOME"
echo "=========================================="

GRADLE_OK=0
# 空数组用 ${arr[@]+...} 展开：macOS 自带的 bash 3.2 在 set -u 下不接受空数组展开
if ./gradlew "${TASKS[@]}" ${TEST_ARGS[@]+"${TEST_ARGS[@]}"} ${GRADLE_EXTRA[@]+"${GRADLE_EXTRA[@]}"}; then
    GRADLE_OK=1
fi

# ---------- 汇总 ----------
RESULTS_DIR="$PROJECT_DIR/composeApp/build/test-results"
sum_attr() {
    # 用法: sum_attr <属性名> <目录>…  从 JUnit XML 的 testsuite 标签累加某个数字属性
    local attr="$1"; shift
    find "$@" -name "TEST-*.xml" -print0 2>/dev/null |
        xargs -0 cat 2>/dev/null |
        grep -o "$attr=\"[0-9]*\"" | sed "s/$attr=\"//;s/\"//" |
        awk '{s += $1} END { print s + 0 }'
}

XML_DIRS=()
[ -d "$RESULTS_DIR/desktopTest" ] && XML_DIRS+=("$RESULTS_DIR/desktopTest")
if [ "$RUN_IOS" -eq 1 ] && [ -d "$RESULTS_DIR/iosSimulatorArm64Test" ]; then
    XML_DIRS+=("$RESULTS_DIR/iosSimulatorArm64Test")
fi

if [ ${#XML_DIRS[@]} -gt 0 ]; then
    TOTAL=$(sum_attr tests "${XML_DIRS[@]}")
    FAILED=$(sum_attr failures "${XML_DIRS[@]}")
    ERRORED=$(sum_attr errors "${XML_DIRS[@]}")
    SKIPPED=$(sum_attr skipped "${XML_DIRS[@]}")
    PASSED=$((TOTAL - FAILED - ERRORED - SKIPPED))

    if [ "$GRADLE_OK" -eq 1 ] && [ "$((FAILED + ERRORED))" -eq 0 ]; then
        echo "=========================================="
        echo "✅ 全部通过: ${PASSED} 通过 / ${SKIPPED} 跳过（共 ${TOTAL}）"
    else
        echo "=========================================="
        echo "❌ 测试未全部通过: ${PASSED} 通过 / ${FAILED} 失败 / ${ERRORED} 错误 / ${SKIPPED} 跳过（共 ${TOTAL}）"
        echo "---------- 失败用例 ----------"
        find "${XML_DIRS[@]}" -name "TEST-*.xml" -print0 2>/dev/null |
            xargs -0 awk '
                /<testcase /{ tc = $0 }
                /<failure |<error /{
                    line = tc
                    sub(/^.*name="/, "", line); sub(/".*$/, "", line)
                    cls = tc
                    match(tc, /classname="[^"]*"/)
                    cls = substr(tc, RSTART + 11, RLENGTH - 12)
                    msg = $0
                    match(msg, /message="[^"]*"/)
                    msg = substr(msg, RSTART + 9, RLENGTH - 10)
                    print "  " cls "  »  " line
                    print "      " msg
                }'
    fi
    echo ""
    echo "详细报告:"
    echo "  file://$PROJECT_DIR/composeApp/build/reports/tests/desktopTest/index.html"
    [ "$RUN_IOS" -eq 1 ] && echo "  file://$PROJECT_DIR/composeApp/build/reports/tests/iosSimulatorArm64Test/index.html"
    echo "=========================================="
fi

[ "$GRADLE_OK" -eq 1 ] || exit 1
