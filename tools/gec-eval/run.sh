#!/usr/bin/env bash
# Runs the issue #41 measurement on one device and scores it.
#
#   tools/gec-eval/run.sh                 # the only connected device
#   tools/gec-eval/run.sh <serial>        # a specific one, from `adb devices`
#
# Takes several minutes: fifty sentences at roughly 5-8 s each on a Pixel 10 Pro.
# The model must already be downloaded in the app.
#
# This drives the run with `am instrument` rather than `connectedDebugAndroidTest`, because
# AGP uninstalls both APKs when a connected test finishes. That uninstall deletes
# /sdcard/Android/data/<pkg>/ and the app's internal files with it — so the results file was
# gone before it could be pulled, and the 1.2 GB model had to be downloaded again. Installing
# the two APKs by hand and leaving them installed is the whole point of doing it this way.

set -euo pipefail

cd "$(dirname "$0")/../.."

SERIAL="${1:-}"
if [ -z "$SERIAL" ]; then
  SERIAL="$(adb devices | awk 'NR>1 && $2=="device" {print $1}' | head -1)"
fi
if [ -z "$SERIAL" ]; then
  echo "No device. Connect one, or pass a serial from 'adb devices'." >&2
  exit 1
fi

PKG=com.raghavsatyadev.grammarfix
TEST_PKG="$PKG.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
CLASS=com.raghavsatyadev.grammarfix.engine.GecEvalTest
REMOTE="/sdcard/Android/data/$PKG/files/gec-eval/results.tsv"
APK=androidCMP/build/outputs/apk/debug/androidCMP-debug.apk
TEST_APK=androidCMP/build/outputs/apk/androidTest/debug/androidCMP-debug-androidTest.apk
LOCAL_DIR="tools/gec-eval/results"
NAME="$(adb -s "$SERIAL" shell getprop ro.product.model | tr -d '\r' | tr ' ' '_')"
LOCAL="$LOCAL_DIR/results-$NAME.tsv"
LOGCAT="$LOCAL_DIR/logcat-$NAME.txt"

mkdir -p "$LOCAL_DIR"

dev() { MSYS_NO_PATHCONV=1 command adb -s "$SERIAL" "$@"; }

echo "==> Device: $NAME ($SERIAL)"

# A daemon that has already picked the wrong JDK keeps using it, so stop it first.
# See issue #36 — the Antigravity-bundled JRE has no jlink and fails the build.
JDK="${GRAMMARFIX_JDK:-C:\Program Files\Java\jdk-21.0.12}"
echo "==> Stopping any stale Gradle daemon (issue #36)"
./gradlew --stop >/dev/null 2>&1 || true

echo "==> Building the app and test APKs"
./gradlew :androidCMP:assembleDebug :androidCMP:assembleDebugAndroidTest \
  -Dorg.gradle.java.home="$JDK" \
  --no-daemon

echo "==> Installing (and leaving installed — see the note at the top of this file)"
dev install -r -d "$APK" >/dev/null
dev install -r -d -t "$TEST_APK" >/dev/null

if ! dev shell "run-as $PKG ls files/qwen3-1.7b-int4.gguf" >/dev/null 2>&1; then
  echo "The model is not on the device. Download it in the app, then run this again." >&2
  exit 1
fi

echo "==> Running the sample set on device. This takes a few minutes."
dev logcat -c || true
dev logcat -v time "GRAMMARFIX_GEC_EVAL:I" "*:S" > "$LOGCAT" &
LOGCAT_PID=$!
trap 'kill "$LOGCAT_PID" 2>/dev/null || true' EXIT

RUN_LOG="$LOCAL_DIR/instrument-$NAME.txt"
dev shell am instrument -w -e class "$CLASS" "$TEST_PKG/$RUNNER" 2>&1 | tee "$RUN_LOG"

kill "$LOGCAT_PID" 2>/dev/null || true

if ! grep -q "^OK (" "$RUN_LOG"; then
  echo "The instrumentation run did not report OK - see $RUN_LOG and $LOGCAT" >&2
  exit 1
fi

echo "==> Pulling results"
dev pull "$REMOTE" "$LOCAL"

echo "==> Scoring"
python tools/gec-eval/score.py "$LOCAL" "${@:2}"

echo
echo "Raw results: $LOCAL"
echo "Device log:  $LOGCAT"
echo "Paste the summary into issue #41."
