#!/usr/bin/env bash
# Idempotent bootstrap for the qqhook (QQ Zygisk/Magisk module) dev environment.
# Mirrors the steps in .github/workflows/ci.yml so local builds match CI.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# --- 1. Git submodules (AndroidVMTools, ZygoteLoader) are included builds ---
git submodule update --init --recursive

# --- 2. JDK (the base image ships Temurin/OpenJDK 21, matching CI) ---
if [ -z "${JAVA_HOME:-}" ] && [ -d /usr/lib/jvm/java-21-openjdk-amd64 ]; then
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
fi

# --- 3. Android SDK (command-line tools + CI package set) ---
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
CMDLINE_TOOLS_VERSION="16111833"

if [ ! -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "Installing Android command-line tools..."
  mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
  tmpzip="$(mktemp --suffix=.zip)"
  curl -fsSL -o "$tmpzip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
  rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/latest" "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools"
  unzip -q "$tmpzip" -d "$ANDROID_SDK_ROOT/cmdline-tools"
  mv "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  rm -f "$tmpzip"
fi
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

# Accept licenses so Gradle can also auto-fetch the NDK the submodule pins (28.2.x).
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager --install \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;36.0.0" \
  "ndk;27.2.12479018" \
  "cmake;3.22.1" >/dev/null

# --- 4. Point Gradle at the SDK (local.properties is gitignored) ---
echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties

# --- 5. Persist toolchain env for interactive shells ---
MARKER="# qqhook-dev-env"
if ! grep -qF "$MARKER" "$HOME/.bashrc" 2>/dev/null; then
  {
    echo "$MARKER"
    echo "export JAVA_HOME=\"${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}\""
    echo "export ANDROID_SDK_ROOT=\"$ANDROID_SDK_ROOT\""
    echo "export ANDROID_HOME=\"$ANDROID_SDK_ROOT\""
    echo 'export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"'
  } >> "$HOME/.bashrc"
fi

# --- 6. WebUI (KernelSU webroot): install deps and build so webroot/dist exists ---
# The Gradle `mergeMagisk` task copies webroot/dist into the packaged module.
# node/pnpm are nvm-managed in the base image; make sure they're on PATH.
if ! command -v pnpm >/dev/null 2>&1 && [ -d "$HOME/.nvm/versions/node" ]; then
  node_bin="$(find "$HOME/.nvm/versions/node" -maxdepth 2 -type d -name bin 2>/dev/null | sort -V | tail -1)"
  [ -n "$node_bin" ] && export PATH="$node_bin:$PATH"
fi
corepack enable >/dev/null 2>&1 || true
( cd webroot && pnpm install --frozen-lockfile && pnpm run build )

# --- 7. Warm Gradle caches and validate the full module build end-to-end ---
chmod +x gradlew
./gradlew :app:assembleRelease --no-daemon --stacktrace

echo "Environment ready. Module: app/build/outputs/magisk/release/qqhook.zip"
