#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WORK="$ROOT/.hb04_payload"
ZIP="$WORK/pocketsteward_payload.zip"
SRC="$WORK/src"
EXPECTED_SHA="9ac97bbe03eedcf5216a50c833e4bb1efd041b2bfdda9e8b2f4e2442ac7ecc89"

materialize_payload() {
  if [ -x "$SRC/gradlew" ]; then
    return
  fi
  rm -rf "$WORK"
  mkdir -p "$WORK" "$SRC"
  cat "$ROOT"/hb04_payload/chunk_* | base64 -d > "$ZIP"
  printf '%s  %s\n' "$EXPECTED_SHA" "$ZIP" | sha256sum -c -
  unzip -q "$ZIP" -d "$SRC"
  chmod +x "$SRC/gradlew"
}

materialize_payload

for arg in "$@"; do
  if [ "$arg" = "assembleDebug" ]; then
    (
      cd "$SRC"
      ./gradlew assembleRelease --stacktrace
    )
    SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -z "$SDK" ]; then
      echo "Android SDK environment missing" >&2
      exit 31
    fi
    BT=$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)
    if [ -z "$BT" ] || [ ! -x "$BT/zipalign" ] || [ ! -f "$BT/lib/apksigner.jar" ]; then
      echo "Required Android build-tools not found under $SDK" >&2
      exit 32
    fi
    IN=$(find "$SRC/app/build/outputs/apk/release" -maxdepth 1 -type f -name '*-unsigned.apk' | head -n 1)
    if [ -z "$IN" ] || [ ! -f "$IN" ]; then
      echo "Unsigned release APK not produced" >&2
      exit 33
    fi
    OUT="$ROOT/app/build/outputs/apk/debug"
    mkdir -p "$OUT"
    "$BT/zipalign" -f -p 4 "$IN" "$OUT/PocketSteward-HB04-unsigned-aligned.apk"
    cp "$BT/lib/apksigner.jar" "$OUT/apksigner-tool.apk"
    sha256sum "$OUT/PocketSteward-HB04-unsigned-aligned.apk"
    exit 0
  fi
done

cd "$SRC"
exec ./gradlew "$@"
