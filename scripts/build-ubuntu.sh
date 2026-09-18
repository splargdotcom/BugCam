#!/usr/bin/env bash
# Ubuntu 26.04 / Linux x86_64. Uses the genuine, checked-in Gradle wrapper.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
install_deps=false
for arg in "$@"; do
    case "$arg" in
        --install-deps) install_deps=true ;;
        --help|-h)
            echo "Usage: $0 [--install-deps]"
            echo "Installs missing Android SDK components in ANDROID_HOME or ~/Android/Sdk."
            echo "--install-deps also installs OpenJDK 17, curl, unzip and Python through apt."
            exit 0 ;;
        *) echo "Unknown option: $arg" >&2; exit 2 ;;
    esac
done
if "$install_deps"; then
    sudo apt-get update
    sudo apt-get install -y openjdk-17-jdk-headless curl unzip python3 ca-certificates
fi
for tool in java javac curl unzip python3 sha256sum; do
    command -v "$tool" >/dev/null || { echo "Missing $tool. Run with --install-deps." >&2; exit 1; }
done
# AGP 9.2 supports JDK 17. Prefer the explicit Ubuntu JDK over an arbitrary system default.
if [[ -z "${JAVA_HOME:-}" && -x /usr/lib/jvm/java-17-openjdk-amd64/bin/javac ]]; then
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
fi
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
mkdir -p "$ANDROID_HOME"
sdk_has_platform=false
for platform in android-37 android-37.0; do
    [[ ! -f "$ANDROID_HOME/platforms/$platform/android.jar" ]] || sdk_has_platform=true
done
if ! "$sdk_has_platform" || [[ ! -x "$ANDROID_HOME/build-tools/36.0.0/aapt2" ]] ||
    [[ ! -x "$ANDROID_HOME/platform-tools/adb" ]]; then
    echo "Installing Android SDK components. Google's SDK terms apply: https://developer.android.com/studio/terms"
    sdk_bin="$ANDROID_HOME/cmdline-tools/latest/bin/android"
    if [[ ! -x "$sdk_bin" ]]; then
        [[ "$(uname -m)" == x86_64 ]] || { echo "Use Android Studio SDK Manager on this architecture." >&2; exit 1; }
        download_dir="$(mktemp -d)"
        trap 'rm -rf -- "$download_dir"' EXIT
        curl --fail --location --retry 3 \
            https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip \
            --output "$download_dir/tools.zip"
        echo "0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8  $download_dir/tools.zip" | sha256sum --check --status
        unzip -q "$download_dir/tools.zip" -d "$download_dir/unpacked"
        mkdir -p "$ANDROID_HOME/cmdline-tools"
        if [[ -e "$ANDROID_HOME/cmdline-tools/latest" ]]; then
            # Preserve an older installation rather than replacing it in place.
            tools_target="$ANDROID_HOME/cmdline-tools/bugcam-16111833"
        else
            tools_target="$ANDROID_HOME/cmdline-tools/latest"
        fi
        if [[ ! -d "$tools_target" ]]; then
            mv "$download_dir/unpacked/cmdline-tools" "$tools_target"
        fi
        sdk_bin="$tools_target/bin/android"
    fi
    # Current official command-line tools use Android CLI. A legacy sdkmanager is unnecessary.
    # The launcher downloads Google's CLI on first use. Suppress optional telemetry.
    "$sdk_bin" --no-metrics --sdk="$ANDROID_HOME" sdk install platforms/android-37.0
    "$sdk_bin" --no-metrics --sdk="$ANDROID_HOME" sdk install build-tools/36.0.0
    "$sdk_bin" --no-metrics --sdk="$ANDROID_HOME" sdk install platform-tools
fi
# Some Android CLI versions print download errors but return zero. Verify installation.
if [[ ! -f "$ANDROID_HOME/platforms/android-37.0/android.jar" && ! -f "$ANDROID_HOME/platforms/android-37/android.jar" ]]; then
    echo "Android 17 SDK installation failed. Install platform 37.0 in Android Studio SDK Manager, then retry." >&2
    exit 1
fi
for installed in build-tools/36.0.0/aapt2 platform-tools/adb; do
    [[ -x "$ANDROID_HOME/$installed" ]] || { echo "SDK tool missing: $installed" >&2; exit 1; }
done
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$ANDROID_HOME/platform-tools:$PATH"
# Safely write a Java .properties path, including spaces/backslashes, without shell interpolation.
python3 - "$project_dir/local.properties" "$ANDROID_HOME" <<'PY'
import pathlib, sys
value = sys.argv[2].replace('\\', '\\\\').replace(':', '\\:').replace(' ', '\\ ')
pathlib.Path(sys.argv[1]).write_text('sdk.dir=' + value + '\n', encoding='utf-8')
PY
cd "$project_dir"
./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/BugCam-debug.apk
sha256sum dist/BugCam-debug.apk
echo "Built: $project_dir/dist/BugCam-debug.apk"
