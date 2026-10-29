# Perfetto profiling — record and analyze traces on a device

Use for jank, slow startup, CPU hotspots or memory leaks. The Android CLI has no profiler
command; recording uses Perfetto's official helper scripts, wrapped by `scripts/perfetto/trace.sh`.

## Record

Needs: a device on adb (`adb devices`), GrammarFix installed, Python on PATH. The script downloads
the helpers once into `tmp/perfetto/` (gitignored) and writes traces there.

```bash
scripts/perfetto/trace.sh                         # 10 s jank trace: frames, sched, gfx/view/wm/am/input
scripts/perfetto/trace.sh cpu_profile -d 10000    # CPU callstack sampling (native/llama code)
scripts/perfetto/trace.sh heap_profile            # native heap (malloc/free), stop with Ctrl+C
scripts/perfetto/trace.sh java_heap_dump -o tmp/perfetto/heap.perfetto-trace
```

- Open the app and start the flow you want to measure before (or right after) starting the trace.
- Jank config: `scripts/perfetto/app_jank.pftxt` (64 MB ring buffer, 10 s, frametimeline +
  `atrace_apps` for `com.raghavsatyadev.grammarfix`). For other data sources, start from the
  example configs in the `android-profiler` skill and run
  `python tmp/perfetto/record_android_trace -c <config> -o tmp/perfetto/<name>.perfetto-trace -n`.

## Analyze

Load the `android-profiler` skill and follow its analysis orchestrator (PerfettoSQL via
trace_processor: jank frames, startup, thread states, binder). Or open the trace in
https://ui.perfetto.dev. Never commit traces.
