# Tone detection spike (issue #32)

Answers one question before any UI is built: **can Qwen3 1.7B at Q4_K_M label the tone of a
message usefully, and cheaply enough to run on every settled edit?**

## Run it

```bash
tools/tone-spike/run.sh              # the only connected device
tools/tone-spike/run.sh <serial>     # a specific one, from `adb devices`
SKIP_BUILD=1 tools/tone-spike/run.sh # reuse APKs you already built
VARIANTS=SCORED_CACHED,SHIPPING_CACHED ARGS="-e rewriteBaseline 0" tools/tone-spike/run.sh  # a subset
```

The model must already be downloaded in the app. Like `tools/gec-eval/`, this installs both
APKs and drives the run with `am instrument`, never `connectedDebugAndroidTest`, which
uninstalls the app and deletes the model. Installing drops the accessibility grant: re-enable
the service afterwards.

To re-score a results file without the device:

```bash
python tools/tone-spike/score.py tools/tone-spike/results/results-Pixel_10_Pro.tsv
```

## What it measures

- **Vocabulary** — eight fixed labels: formal, casual, friendly, direct, uncertain, urgent,
  apologetic, frustrated. Fixed, so it is testable and can be stored like a preset id.
- **Samples** — hand-labelled messages in `ToneSpikeDataset.kt`. Each lists every label a
  human reader would accept, dominant first. Adding samples is a change to that file alone.
- **Prompts** — `FEW_SHOT`: the spike's labels-only prompt (`maxOutputTokens = 16`, five worked
  examples not in the set). `SHIPPING`: the prompt the app ships (`ToneDetection`), labels plus
  a one-line summary in one call. `-e zeroShot 1` adds the zero-shot variant #49 measured.
- **Cold vs cached** — each prompt runs `_COLD` (cleared KV cache, full prefill, the path
  proofreading uses) and `_CACHED` (the saved KV state of the fixed opening is reused, the path
  the app's tone read uses; see `grammarfix_llama.cpp`). Same prompt, same text.
- **Baseline** — the first three samples through the shipping Formal rewrite, timed on the
  same device in the same run (`-e rewriteBaseline 0` skips it).

The scorer reports top-1 accuracy (the model's first label is one the labeller accepted), the
any-label hit rate, per-label recall and precision, latency, and every wrong top label. For each
cold/cached pair it also counts identical answers and prints every sample where they differ.

The model does the classifying. The harness only splits its answer on commas and drops
anything outside the vocabulary, which it reports rather than maps.

## Files

| | |
| --- | --- |
| `androidCMP/src/androidTest/.../ToneSpikeDataset.kt` | the samples and their labels |
| `androidCMP/src/androidTest/.../ToneSpikePrompt.kt` | the spike's labels-only prompt |
| `composeApp/.../engine/ToneDetection.kt` | the shipping vocabulary, prompt and parser |
| `androidCMP/src/androidTest/.../ToneSpikeTest.kt` | runs every prompt cold and cached, writes TSVs |
| `score.py` | scores a results file |
| `run.sh` | builds, installs, runs on device, pulls and scores |
