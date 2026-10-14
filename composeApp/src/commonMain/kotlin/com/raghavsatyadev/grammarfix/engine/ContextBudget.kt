package com.raghavsatyadev.grammarfix.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The fixed token window an engine has to fit a whole request into. An engine that manages its own
 * window has no [TokenWindow]; see [LlmDriver.tokenWindow].
 */
interface TokenWindow {
  /** Tokens the loaded context holds, prompt and answer together. */
  val contextTokens: Int

  /** What [text] costs in this model's vocabulary. */
  suspend fun countTokens(text: String): Int

  /** What [prompt] costs before any of the user's text is added to it. */
  suspend fun overheadTokens(prompt: CorrectionPrompt): Int
}

/**
 * Divides the context window between the tone's prompt, the user's text and the answer.
 *
 * The tone's own `maxOutputTokens` is a floor, not the whole story: every tone echoes the user's
 * text back, so an answer needs room in proportion to what it was given. A fixed cap is what made a
 * long request come back cut off mid-sentence, and reserving that cap up front is what made a
 * longer one fail outright.
 */
object ContextBudget {

  /**
   * Held back because a run of text tokenises to slightly fewer tokens than its pieces do
   * separately, and the request is rejected outright if the sum runs low.
   */
  const val SAFETY_MARGIN_TOKENS = 48

  /** Room every answer gets beyond what its input implies, so a short chunk can still finish. */
  const val ANSWER_HEADROOM_TOKENS = 64

  /**
   * Roughly what a reasoning tone spends on `<think>` before it answers, as a multiple of the
   * answer itself. It comes out of the same budget, so it has to be budgeted for.
   */
  const val REASONING_MULTIPLIER = 1.8f

  /**
   * Plans [prompt] against [window].
   *
   * [maxInputTokens] is zero when the tone's own prompt leaves no room for the user's text — a
   * custom tone long enough to fill the window on its own.
   */
  data class Plan(
    val maxInputTokens: Int,
    val overheadTokens: Int,
    val contextTokens: Int,
    val growth: Float,
  ) {
    /** How much room to give the answer for a chunk that measured [inputTokens]. */
    fun answerTokens(inputTokens: Int, floor: Int): Int {
      val wanted = max((inputTokens * growth).roundToInt() + ANSWER_HEADROOM_TOKENS, floor)
      val room = contextTokens - overheadTokens - inputTokens - SAFETY_MARGIN_TOKENS
      return max(1, min(wanted, room))
    }
  }

  suspend fun plan(prompt: CorrectionPrompt, window: TokenWindow): Plan {
    val overhead = window.overheadTokens(prompt)
    // A reasoning tone pays for the answer twice over: once thinking, once saying it.
    val growth = prompt.outputGrowth * if (prompt.enforceNoThink) 1f else REASONING_MULTIPLIER
    val usable = window.contextTokens - overhead - SAFETY_MARGIN_TOKENS - ANSWER_HEADROOM_TOKENS
    val maxInput = (usable / (1f + growth)).toInt()
    return Plan(max(0, maxInput), overhead, window.contextTokens, growth)
  }
}
