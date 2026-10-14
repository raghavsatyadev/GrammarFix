package com.raghavsatyadev.grammarfix.engine

/**
 * What to ask the model, independent of any model's prompt format. Each [LlmDriver] renders it the
 * way its model expects: ChatML turns for Qwen3 ([QwenPromptBuilder]).
 */
data class CorrectionPrompt(
  val systemPrompt: String,
  /** Worked examples as (input, corrected output) pairs. */
  val examples: List<Pair<String, String>>,
  val userText: String,
  val enforceNoThink: Boolean = true,
  /**
   * How many tokens the answer may use. A tone that expands text, or one that reasons before
   * answering, needs more room than the proofreading pass this defaults to.
   */
  val maxOutputTokens: Int = TonePreset.DEFAULT_MAX_OUTPUT_TOKENS,
  /** How long the answer runs next to [userText]; see [TonePreset.outputGrowth]. */
  val outputGrowth: Float = 1f,
  /**
   * Lets the driver reuse the saved KV state of this prompt's fixed opening (system turn and
   * examples) instead of prefilling it again. Only the tone read sets this (#32): reusing a cached
   * opening can change a greedy answer by a token, which proofreading and rewrites must never risk.
   * A driver without such a cache ignores it.
   */
  val reusePrefix: Boolean = false,
  /**
   * The start of the model's own answer, written for it: the answer continues from here. Scoring
   * ([LlmDriver.scoreChoices]) needs it to put each candidate in the place the worked examples
   * taught (`"Tone:"` then `" formal"`). Empty for every generated correction.
   */
  val answerPrefix: String = "",
) {
  companion object {
    fun forText(text: String, enforceNoThink: Boolean = true) =
      CorrectionPrompt(
        Prompts.Proofread.SYSTEM,
        Prompts.Proofread.EXAMPLES,
        text,
        enforceNoThink,
      )

    /**
     * Asks [tone] of [text]. [fastMode] is the user's global preference for skipping the model's
     * reasoning step; a tone that declares [TonePreset.usesThinking] keeps that step regardless.
     */
    fun forTone(tone: TonePreset, text: String, fastMode: Boolean = true) =
      CorrectionPrompt(
        systemPrompt = tone.systemPrompt,
        examples = tone.examples,
        userText = text,
        enforceNoThink = tone.enforceNoThink(fastMode),
        maxOutputTokens = tone.maxOutputTokens,
        outputGrowth = tone.outputGrowth,
      )
  }
}
