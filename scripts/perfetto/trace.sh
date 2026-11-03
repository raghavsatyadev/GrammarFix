#!/usr/bin/env bash
# Record a Perfetto trace of GrammarFix on the connected device.
#   scripts/perfetto/trace.sh                      # 10 s jank trace (app_jank.pftxt)
#   scripts/perfetto/trace.sh cpu_profile -d 10000 # any helper: cpu_profile, heap_profile, java_heap_dump
# Helper scripts are downloaded once into tmp/perfetto/ (gitignored). Needs Python on PATH.
set -euo pipefail
cd "$(dirname "$0")/../.."
PKG=com.raghavsatyadev.grammarfix
DIR=tmp/perfetto
URL=https://raw.githubusercontent.com/google/perfetto/main/tools
mkdir -p "$DIR"
fetch() { [ -f "$DIR/$1" ] || curl -fsSL -o "$DIR/$1" "$URL/$1"; }
PY=$(command -v python3 || command -v python)
OUT="$DIR/$(date +%Y%m%d-%H%M%S)"
if [ $# -eq 0 ]; then
  fetch record_android_trace
  "$PY" "$DIR/record_android_trace" -c scripts/perfetto/app_jank.pftxt -o "$OUT.perfetto-trace" -n
else
  tool=$1; shift; fetch "$tool"
  "$PY" "$DIR/$tool" -n "$PKG" "$@"
fi
