#!/usr/bin/env bash
#
# Restores the two QAIRT pieces that cannot live in git, so the NPU detector backend works.
#
# Why this script exists: both paths below are gitignored — the CPU-side libraries are
# Qualcomm redistributables and the SDK is ~550 MB — so `git reset --hard`, `git clean -x`
# or a fresh clone silently removes them. The build still succeeds afterwards. The only
# symptom is the NPU backend reporting itself unavailable at runtime:
#
#     litert_npu   UNAVAILABLE   built without the QAIRT SDK headers
#
# Run this, rebuild, done.
#
#   ./tools/restore-qairt.sh [path-to-extracted-qairt-sdk]
#
# Defaults to ~/Downloads/qairt/<version>, or $QAIRT_SDK if set.

set -euo pipefail

VERSION="2.49.0.260730"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="${1:-${QAIRT_SDK:-$HOME/Downloads/qairt/$VERSION}}"

JNI_DIR="$REPO/androidApp/src/main/jniLibs/arm64-v8a"
TOOLS_DIR="$REPO/tools/qairt/$VERSION"

# CPU side. Everything here is arm64 — the Hexagon builds must stay out, because
# libQnnSystem.so ships under the same name for both architectures and a mixed directory
# makes the DSP resolve the dependency to the wrong ELF. The DSP-side files live in
# androidApp/src/main/assets/qnn/hexagon-v73/ and are small enough to be committed.
CPU_LIBS=(
    libQnnTFLiteDelegate.so          # TFLite <-> QNN bridge
    libQnnHtp.so                     # HTP backend
    libQnnHtpPrepare.so              # compiles the graph on device; 75 MB of the total
    libQnnHtpV73Stub.so              # v73 — verified with qnn-platform-validator --coreVersion
    libQnnHtpV73CalculatorStub.so
    libQnnSystem.so
)

fail() { printf '\n\033[31m%s\033[0m\n' "$*" >&2; exit 1; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }

[ -d "$SRC" ] || fail "QAIRT SDK not found at: $SRC
Pass the path as an argument, or set QAIRT_SDK.
Download: https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk"

[ -f "$SRC/include/QNN/TFLiteDelegate/QnnTFLiteDelegate.h" ] \
    || fail "$SRC does not look like a QAIRT SDK (no QNN/TFLiteDelegate headers)"

echo "Restoring from $SRC"

echo
echo "CPU libraries -> androidApp/src/main/jniLibs/arm64-v8a/"
mkdir -p "$JNI_DIR"
for lib in "${CPU_LIBS[@]}"; do
    [ -f "$SRC/lib/aarch64-android/$lib" ] || fail "missing from SDK: lib/aarch64-android/$lib"
    cp "$SRC/lib/aarch64-android/$lib" "$JNI_DIR/"
    ok "$lib"
done

# Headers only — the JNI shim in src/main/cpp includes them, and CMake falls back to a stub
# (NPU permanently unavailable) when they are absent. bin/aarch64-android carries
# qnn-platform-validator, which is how the HTP architecture was identified in the first place
# and the fastest way to tell a device problem from a code problem.
echo
echo "SDK headers and on-device tools -> tools/qairt/$VERSION/"
mkdir -p "$TOOLS_DIR/bin"
rm -rf "$TOOLS_DIR/include"
cp -R "$SRC/include" "$TOOLS_DIR/"
ok "include/"
if [ -d "$SRC/bin/aarch64-android" ]; then
    rm -rf "$TOOLS_DIR/bin/aarch64-android"
    cp -R "$SRC/bin/aarch64-android" "$TOOLS_DIR/bin/"
    ok "bin/aarch64-android/ (qnn-platform-validator)"
fi

echo
echo "Verifying"
[ -f "$TOOLS_DIR/include/QNN/TFLiteDelegate/QnnTFLiteDelegate.h" ] \
    || fail "headers did not land — the NPU backend would build as a stub"
ok "QnnTFLiteDelegate.h present, CMake will compile the real shim"
count=$(find "$JNI_DIR" -name 'libQnn*.so' | wc -l | tr -d ' ')
[ "$count" = "${#CPU_LIBS[@]}" ] || fail "expected ${#CPU_LIBS[@]} CPU libraries, found $count"
ok "$count CPU libraries in place"

cat <<'EOF'

Done. Now:

    ./gradlew :androidApp:assembleDebug

Confirm it worked — the startup probe logs one line per backend:

    adb logcat -s CamPerf | grep litert_npu

"OK" means the NPU is running. "UNAVAILABLE" with a reason means something above is still
missing; the reason names the missing piece.
EOF
