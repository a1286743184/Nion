#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# 自动检测环境：Git Bash（Windows）或 WSL2
if [[ "$(uname)" == MINGW* || "$(uname)" == MSYS* ]]; then
    # Windows Git Bash
    SDK_ROOT="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}"
    NDK_PARENT="$SDK_ROOT/ndk"
    TOOLCHAIN_HOST="windows-x86_64"
    CLANG_SUFFIX=".cmd"
    AR_NAME="llvm-ar.exe"
    LINKER_SUFFIX=".cmd"
    # Windows 下 /tmp 映射到 Temp 目录
    UNIFFI_OUT_DIR="$TEMP/uniffi-output"
else
    # WSL2
    SDK_ROOT="${ANDROID_HOME:-$HOME/android-sdk}"
    NDK_PARENT="$SDK_ROOT/ndk"
    TOOLCHAIN_HOST="linux-x86_64"
    CLANG_SUFFIX=""
    AR_NAME="llvm-ar"
    LINKER_SUFFIX=""
    UNIFFI_OUT_DIR="/tmp/uniffi-output"
fi

# NDK 版本自动检测：取已安装的最高版本（版本号按数字分段比较，
# 避免字典序把 28.2 排到 27.0 前面之外的情况）。
# 可用 NDK_VERSION 环境变量强制指定。
if [ -n "$NDK_VERSION" ]; then
    NDK_PATH="$NDK_PARENT/$NDK_VERSION"
elif [ -d "$NDK_PARENT" ]; then
    NDK_PATH="$NDK_PARENT/$(ls "$NDK_PARENT" | sort -V | tail -1)"
else
    echo "ERROR: 找不到 NDK 目录: $NDK_PARENT" >&2
    exit 1
fi

if [ ! -d "$NDK_PATH" ]; then
    echo "ERROR: NDK 不存在: $NDK_PATH" >&2
    exit 1
fi
echo "使用 NDK: $NDK_PATH"

TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/$TOOLCHAIN_HOST"

# cargo target 目录解析：本机 ~/.cargo/config.toml 里设了 [build] target-dir，
# 产物因此不在仓库内的 target/ 下。直接问 cargo 拿真实路径，
# 避免脚本硬编码路径在换了机器/配置后指向不存在的 .so。
TARGET_DIR="$(cd "$SCRIPT_DIR" && cargo metadata --format-version 1 --no-deps 2>/dev/null \
  | python -c "import sys,json;print(json.load(sys.stdin)['target_directory'])" 2>/dev/null)"
if [ -z "$TARGET_DIR" ]; then
  TARGET_DIR="$SCRIPT_DIR/target"
fi
echo "使用 target 目录: $TARGET_DIR"

export CC_aarch64_linux_android="${TOOLCHAIN}/bin/aarch64-linux-android26-clang${CLANG_SUFFIX}"
export AR_aarch64_linux_android="${TOOLCHAIN}/bin/${AR_NAME}"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="${TOOLCHAIN}/bin/aarch64-linux-android26-clang${LINKER_SUFFIX}"
export CC_x86_64_linux_android="${TOOLCHAIN}/bin/x86_64-linux-android26-clang${CLANG_SUFFIX}"
export AR_x86_64_linux_android="${TOOLCHAIN}/bin/${AR_NAME}"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="${TOOLCHAIN}/bin/x86_64-linux-android26-clang${LINKER_SUFFIX}"

echo "=== Building Rust core for Android ($(uname)) ==="

echo "[1/4] aarch64-linux-android ..."
cd "$SCRIPT_DIR"
cargo build -p nion-core --target aarch64-linux-android --release \
  --config "target.aarch64-linux-android.rustflags=['-C', 'link-arg=-L$TOOLCHAIN/sysroot/usr/lib/aarch64-linux-android/26', '-C', 'link-arg=-Wl,-z,max-page-size=16384', '-C', 'link-arg=-Wl,-z,common-page-size=16384']"

echo "[2/4] x86_64-linux-android ..."
cargo build -p nion-core --target x86_64-linux-android --release \
  --config "target.x86_64-linux-android.rustflags=['-C', 'link-arg=-L$TOOLCHAIN/sysroot/usr/lib/x86_64-linux-android/26', '-C', 'link-arg=-Wl,-z,max-page-size=16384', '-C', 'link-arg=-Wl,-z,common-page-size=16384']"

echo "[3/4] Generating UniFFI bindings ..."
# 先删除再创建：uniffi-bindgen 不会自动建输出目录，rm -rf 后直接调用会报
# "系统找不到指定的路径 (os error 3)"
rm -rf "$UNIFFI_OUT_DIR"
mkdir -p "$UNIFFI_OUT_DIR"
cargo run -p uniffi-bindgen-cli \
  generate --library "$TARGET_DIR/aarch64-linux-android/release/libnion_core.so" \
  --language kotlin --out-dir "$UNIFFI_OUT_DIR"

echo "[4/4] Copying artifacts ..."
cp "$TARGET_DIR/aarch64-linux-android/release/libnion_core.so" \
   "$SCRIPT_DIR/app/app/src/main/jniLibs/arm64-v8a/"
cp "$TARGET_DIR/x86_64-linux-android/release/libnion_core.so" \
   "$SCRIPT_DIR/app/app/src/main/jniLibs/x86_64/"
cp "$UNIFFI_OUT_DIR/uniffi/nion_core/nion_core.kt" \
   "$SCRIPT_DIR/app/app/src/main/java/uniffi/nion_core/nion_core.kt"

# 校验：确保 .so 和 .kt 比核心源码新，否则发出警告
CORE_SRC=$(find "$SCRIPT_DIR/core/src" -name '*.rs' -newer "$SCRIPT_DIR/app/app/src/main/jniLibs/arm64-v8a/libnion_core.so" 2>/dev/null | head -1)
if [ -n "$CORE_SRC" ]; then
    echo "⚠ WARNING: core/src 中有比 .so 更新的文件，可能需要重新编译"
fi

echo "=== Done! ==="
