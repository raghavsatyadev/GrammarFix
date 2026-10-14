package com.raghavsatyadev.grammarfix.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetTest {

  private fun window(contextTokens: Int, overhead: Int) =
    object : TokenWindow {
      override val contextTokens = contextTokens

      override suspend fun countTokens(text: String) = text.length

      override suspend fun overheadTokens(prompt: CorrectionPrompt) = overhead
    }

  /** Prompt, input and answer together have to leave the safety margin untouched. */
  @Test
  fun aFullChunkStillFitsTheWindow() = runBlocking {
    DefaultTonePresets.all.forEach { tone ->
      val prompt = CorrectionPrompt.forTone(tone, "", fastMode = true)
      val plan = ContextBudget.plan(prompt, window(2048, overhead = 300))

      val input = plan.maxInputTokens
      val total = 300 + input + plan.answerTokens(input, tone.maxOutputTokens)
      assertTrue("${tone.id} overruns the window: $total", total <= 2048)
    }
  }

  /** A tone that triples its input, and thinks first, gets much less of it per request. */
  @Test
  fun anExpandingToneTakesSmallerChunks() = runBlocking {
    val polish = CorrectionPrompt.forTone(DefaultTonePresets.POLISH, "", fastMode = true)
    val elaborate = CorrectionPrompt.forTone(DefaultTonePresets.ELABORATE, "", fastMode = true)
    val window = window(2048, overhead = 300)

    val polishInput = ContextBudget.plan(polish, window).maxInputTokens
    val elaborateInput = ContextBudget.plan(elaborate, window).maxInputTokens

    assertTrue(polishInput > 700)
    assertTrue("elaborate took $elaborateInput", elaborateInput < polishInput / 3)
  }

  /** Reasoning comes out of the same budget, so it has to be budgeted for. */
  @Test
  fun aReasoningToneTakesSmallerChunksThanTheSameToneInFastMode() = runBlocking {
    val thinking = CorrectionPrompt.forTone(DefaultTonePresets.SHORTEN, "", fastMode = true)
    val notThinking = thinking.copy(enforceNoThink = true)
    val window = window(2048, overhead = 300)

    assertTrue(
      ContextBudget.plan(thinking, window).maxInputTokens <
        ContextBudget.plan(notThinking, window).maxInputTokens
    )
  }

  /** Short requests keep the tone's own cap, so nothing about them changes. */
  @Test
  fun aShortInputKeepsThePresetsOwnAnswerBudget() = runBlocking {
    val prompt = CorrectionPrompt.forTone(DefaultTonePresets.POLISH, "", fastMode = true)
    val plan = ContextBudget.plan(prompt, window(2048, overhead = 300))

    assertEquals(
      TonePreset.DEFAULT_MAX_OUTPUT_TOKENS,
      plan.answerTokens(inputTokens = 10, floor = TonePreset.DEFAULT_MAX_OUTPUT_TOKENS),
    )
  }

  /** Past that, the answer gets room in proportion to what it was asked to rewrite. */
  @Test
  fun aLongerInputGetsAProportionallyLongerAnswer() = runBlocking {
    val prompt = CorrectionPrompt.forTone(DefaultTonePresets.POLISH, "", fastMode = true)
    val plan = ContextBudget.plan(prompt, window(2048, overhead = 300))

    val answer = plan.answerTokens(inputTokens = 600, floor = TonePreset.DEFAULT_MAX_OUTPUT_TOKENS)
    assertEquals(600 + ContextBudget.ANSWER_HEADROOM_TOKENS, answer)
  }

  /** A custom tone long enough to fill the window on its own leaves nothing to rewrite. */
  @Test
  fun aPromptThatFillsTheWindowLeavesNoRoom() = runBlocking {
    val prompt = CorrectionPrompt.forTone(DefaultTonePresets.POLISH, "", fastMode = true)

    assertEquals(0, ContextBudget.plan(prompt, window(2048, overhead = 2000)).maxInputTokens)
  }
}
