#!/usr/bin/env bash
# Idempotent bootstrap for the qqhook Cloud Agent environment.
# Installs the Android SDK toolchain, initializes submodules, and prepares
# the WebUI so that `./gradlew :app:assembleRelease` works out of the box.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# --- JDK ---------------------------------------------------------------------
# The base image already ships Temurin/OpenJDK 21, which the build requires.
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export JAVA_HOME

# --- Android SDK -------------------------------------------------------------
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$SDKMANAGER" ]; then
  echo ">> Installing Android command-line tools..."
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  tmp_zip="$(mktemp --suffix=.zip)"
  curl -sSL -o "$tmp_zip" \
    https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  rm -rf "$ANDROID_HOME/cmdline-tools/latest" "$ANDROID_HOME/cmdline-tools/cmdline-tools"
  unzip -q "$tmp_zip" -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -f "$tmp_zip"
fi

echo ">> Accepting Android SDK licenses..."
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true

echo ">> Installing Android SDK packages (matches .github/workflows/ci.yml)..."
"$SDKMANAGER" --install \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;36.0.0" \
  "ndk;27.2.12479018" \
  "cmake;3.22.1" >/dev/null

# --- Git submodules ----------------------------------------------------------
echo ">> Initializing git submodules..."
git submodule update --init --recursive

# --- Gradle SDK location -----------------------------------------------------
echo ">> Writing local.properties..."
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
chmod +x gradlew

# --- WebUI (bundled into the module zip via mergeMagisk) ---------------------
if command -v pnpm >/dev/null 2>&1; then
  echo ">> Installing WebUI (webroot) dependencies..."
  (cd webroot && pnpm install --frozen-lockfile)
else
  echo ">> pnpm not found; skipping WebUI dependency install." >&2
fi

# --- Persist toolchain env for interactive shells ----------------------------
PROFILE_LINE_HOME="export ANDROID_HOME=\"$ANDROID_HOME\""
PROFILE_LINE_ROOT="export ANDROID_SDK_ROOT=\"$ANDROID_HOME\""
PROFILE_LINE_JAVA="export JAVA_HOME=\"$JAVA_HOME\""
touch "$HOME/.bashrc"
grep -qxF "$PROFILE_LINE_HOME" "$HOME/.bashrc" || echo "$PROFILE_LINE_HOME" >> "$HOME/.bashrc"
grep -qxF "$PROFILE_LINE_ROOT" "$HOME/.bashrc" || echo "$PROFILE_LINE_ROOT" >> "$HOME/.bashrc"
grep -qxF "$PROFILE_LINE_JAVA" "$HOME/.bashrc" || echo "$PROFILE_LINE_JAVA" >> "$HOME/.bashrc"

echo ">> Environment setup complete."
