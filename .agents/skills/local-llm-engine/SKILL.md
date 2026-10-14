---
name: local-llm-engine
description: Read before changing the on-device engine, Qwen3 GGUF (llama.cpp JNI) — prompt format, model storage, benchmarks, and why Gemini Nano (AICore) was removed.
version: 1.0.0
---

# Local LLM Inference Engine

Every correction comes from the on-device model — see
[pure-local-llm-inference](../../rules/pure-local-llm-inference.md). The engine takes a
`CorrectionPrompt`; `GrammarDiffAnalyzer.computeDiff()` turns input vs output into suggestions.

## Engine: `EngineMode.QWEN3_GGUF` (Universal Offline GGUF)
- **Model:** Qwen 3 1.7B Q4_K_M GGUF (1 282 439 264 bytes when complete; the UI says "1.15 GB").
- **Runtime:** `llamaAndroid` via pinned `llama.cpp` JNI (`b10356`).
- **Prompt:** Rendered via `QwenPromptBuilder.buildPrompt(prompt: CorrectionPrompt)` into ChatML format (`<|im_start|>system...<|im_end|><|im_start|>user...`).
- **Compatibility:** Runs on all Android devices, JVM (desktop testing), and works in background/overlay services.
- **Model Storage:** Downloaded by the app into its own files directory
  (`/data/user/0/com.raghavsatyadev.grammarfix/files/qwen3-1.7b-int4.gguf`). There is no `models/`
  subdirectory. The path comes from `defaultModelPath()`, which is `modelStorageDir()` —
  the app's `filesDir` — plus `MODEL_FILE_NAME`; see `ModelFileStorage.kt`. On a secondary
  user or work profile `/data/user/0` will differ, so read the path from the code rather
  than hardcoding it.

## Gemini Nano (AICore) — removed, do not add it back

AICore runs inference only for the app on screen (`BACKGROUND_USE_BLOCKED`, error 30). The
product is the overlay that corrects text while another app is in the foreground, so Gemini Nano
can never serve it. ML Kit's prompt API also gives no token probabilities, so it cannot score tone
labels. The engine, its UI and the `genai-prompt` dependency were removed.

## Testing & Benchmarking Protocols

1. **Unit Tests (Fake / Mock Drivers):**
   - Always verify `LlmDriver.generate(prompt: CorrectionPrompt, engineMode: EngineMode)`.
   - Ensure unit tests assert on both `prompt.userText` and the builder output format (`QwenPromptBuilder`).

2. **Physical On-Device Benchmarking** — not with `connectedDebugAndroidTest`, which uninstalls
   the app and deletes the downloaded model (android-device-test gotcha 7):
   ```bash
   ./gradlew :androidCMP:installDebug :androidCMP:installDebugAndroidTest
   R=com.raghavsatyadev.grammarfix.test/androidx.test.runner.AndroidJUnitRunner
   C=com.raghavsatyadev.grammarfix.engine.DevicePhysicalBatchTest
   adb shell am instrument -w -e class $C#executePhysical10SentenceBenchmarkOnDevice $R
   adb logcat -s GRAMMARFIX_PHYSICAL_BENCHMARK
   ```
