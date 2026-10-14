---
trigger: model_decision
description: Changing grammar, spelling or tone correction logic or LLM prompts (engine/Prompts.kt, TonePreset.kt, ui/OverlayRewriter.kt and the rest of the correction pipeline). Every fix must come from the on-device LLM, never regex or dictionaries.
---

# RULE: 100% PURE LOCAL LLM INFERENCE (NO HARDCODED STRING REPLACEMENT / REGEX DICTIONARIES)

**Context:**
All grammar, spelling, tense, punctuation, and syntax corrections across GrammarFix MUST rely 100% on the local on-device LLM model inference pipeline (Qwen 3 1.7B GGUF via llama.cpp).

**Strict Constraints:**
1. **Prohibit Hardcoded Dictionaries & Regex Fixes**:
   - Do NOT use hardcoded word-to-word typo dictionaries (`mapOf("teh" to "the", ...)`), preposition rule maps, or regex string replacements to solve grammar or spelling mistakes.
2. **100% Pure LLM Inference**:
   - All sentence proofreading and corrections must be generated exclusively through the on-device LLM inference pipeline:
     `InferenceEngine` -> `QwenPromptBuilder.buildPrompt()` -> `LlmDriver.generate(prompt, engineMode)` -> `cleanLlmOutput(rawOutput)` -> `GrammarDiffAnalyzer.computeDiff()`.
3. **Diff Computation & Suggestions**:
   - Visual suggestions and diff items (`DiffType.REPLACED`, `DiffType.INSERTED`, `DiffType.DELETED`) must be derived strictly by computing the diff between the user's original input text and the LLM's generated output tokens.
4. **Driver Execution**:
   - Execution in production must invoke the on-device model runner (llama.cpp JNI), and tests must verify LLM driver prompt/response integration without regex fallbacks.