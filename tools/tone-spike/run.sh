#!/usr/bin/env bash
# Runs the issue #32 tone-detection spike on one device and scores it.
#
#   tools/tone-spike/run.sh                 # the only connected device
#   tools/tone-spike/run.sh <serial>        # a specific one, from `adb devices`
#
# About eight minutes on a Pixel 10 Pro for 24 samples: two prompts (the spike's labels-only
# few-shot and the shipping labels+summary one), each cold and with the KV-cached opening, plus
# three Formal rewrites timed for comparison. Time grows with ToneSpikeDataset. The model must
# already be downloaded in the app.
#
# Drives the run with `am instrument`, never `connectedDebugAndroidTest`: AGP uninstalls both
# APKs when a connected test finishes, which deletes the 1.2 GB model and the results file.
# See tools/gec-eval/run.sh for the longer story.
#
# Installing replaces the app, which drops the accessibility grant. Re-enable the service after
# the run if you use the overlay on this phone.

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
CLASS=com.raghavsatyadev.grammarfix.engine.ToneSpikeTest
REMOTE_DIR="/sdcard/Android/data/$PKG/files/tone-spike"
APK=androidCMP/build/outputs/apk/debug/androidCMP-debug.apk
TEST_APK=androidCMP/build/outputs/apk/androidTest/debug/androidCMP-debug-androidTest.apk
LOCAL_DIR="tools/tone-spike/results"
NAME="$(adb -s "$SERIAL" shell getprop ro.product.model | tr -d '\r' | tr ' ' '_')"

mkdir -p "$LOCAL_DIR"

dev() { MSYS_NO_PATHCONV=1 command adb -s "$SERIAL" "$@"; }

echo "==> Device: $NAME ($SERIAL)"

if [ -z "${SKIP_BUILD:-}" ]; then
  echo "==> Building the app and test APKs"
  ./gradlew :androidCMP:assembleDebug :androidCMP:assembleDebugAndroidTest --console=plain -q
fi

echo "==> Installing (and leaving installed)"
dev install -r -d "$APK" >/dev/null
dev install -r -d -t "$TEST_APK" >/dev/null

if ! dev shell "run-as $PKG ls files/qwen3-1.7b-int4.gguf" >/dev/null 2>&1; then
  echo "The model is not on the device. Download it in the app, then run this again." >&2
  exit 1
fi

echo "==> Running the sample set on device"
RUN_LOG="$LOCAL_DIR/instrument-$NAME.txt"
# VARIANTS=SCORED_CACHED,SHIPPING_CACHED runs only those passes; ARGS adds any other `-e` pair.
# shellcheck disable=SC2086
dev shell am instrument -w -e class "$CLASS" ${VARIANTS:+-e variants "$VARIANTS"} ${ARGS:-} \
  "$TEST_PKG/$RUNNER" 2>&1 | tee "$RUN_LOG"

if ! grep -q "^OK (" "$RUN_LOG"; then
  echo "The instrumentation run did not report OK - see $RUN_LOG and 'adb logcat -s GRAMMARFIX_TONE_SPIKE'" >&2
  exit 1
fi

echo "==> Pulling results"
dev pull "$REMOTE_DIR/results.tsv" "$LOCAL_DIR/results-$NAME.tsv" >/dev/null
dev pull "$REMOTE_DIR/rewrite-baseline.tsv" "$LOCAL_DIR/rewrite-baseline-$NAME.tsv" >/dev/null 2>&1 || true

echo "==> Scoring"
python tools/tone-spike/score.py "$LOCAL_DIR/results-$NAME.tsv"
