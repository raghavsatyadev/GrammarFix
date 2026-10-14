package com.raghavsatyadev.grammarfix.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a tone reaches the model: its own system prompt, its examples, and its token budget. */
class TonePromptTest {

  private val text = "we gotta ship this thing"

  @Test
  fun polishAsksExactlyWhatTheProofreadingPassAlwaysAsked() {
    val tone = CorrectionPrompt.forTone(DefaultTonePresets.POLISH, text, fastMode = true)

    assertEquals(CorrectionPrompt.forText(text, enforceNoThink = true), tone)
  }

  @Test
  fun eachToneSendsItsOwnSystemPrompt() {
    val prompt = CorrectionPrompt.forTone(DefaultTonePresets.SHORTEN, text)

    assertEquals(DefaultTonePresets.SHORTEN.systemPrompt, prompt.systemPrompt)
    assertEquals(DefaultTonePresets.SHORTEN.examples, prompt.examples)
    assertEquals(DefaultTonePresets.SHORTEN.maxOutputTokens, prompt.maxOutputTokens)
    assertEquals(text, prompt.userText)
  }

  /**
   * Elaborate's one hard rule, pinned where the tone is built (#35).
   *
   * The constraint has to come before anything about length, and the prompt must not ask for a
   * multiple of the original at all. "Roughly two to three times the length" is what turned *we was
   * discusing about new product launch but desicion was not took* into evolving market conditions
   * and a follow-up discussion that nobody had scheduled: given a length to hit and nothing to say,
   * a model fills the gap with plausible invention.
   */
  @Test
  fun elaborateForbidsInventionBeforeItSaysAnythingAboutLength() {
    val prompt = DefaultTonePresets.ELABORATE.systemPrompt

    val constraint = prompt.indexOf("Invent nothing")
    assertTrue("the prompt must forbid invention", constraint >= 0)
    assertTrue("the constraint comes first", constraint < prompt.indexOf("length"))
    assertFalse(
      "no length target to fill",
      prompt.contains(Regex("two to three times|twice the length|times the length")),
    )
  }

  /**
   * And its worked examples must not teach the padding the prompt forbids.
   *
   * The tone shipped with one example that expanded four words into a sentence of invented detail,
   * which is a stronger instruction than any sentence in the prompt. At least one example now has
   * to stay short because its input was thin.
   */
  @Test
  fun elaborateShowsThatAThinInputGetsAThinAnswer() {
    val restrained =
      DefaultTonePresets.ELABORATE.examples.any { (input, output) ->
        output.length <= input.length * 1.5
      }

    assertTrue("no example shows restraint", restrained)
  }

  /** Fast mode is the user's global preference; a rewrite tone keeps the reasoning step anyway. */
  @Test
  fun aRewriteToneOverridesFastMode() {
    assertFalse(
      CorrectionPrompt.forTone(DefaultTonePresets.FORMAL, text, fastMode = true).enforceNoThink
    )
    assertFalse(
      CorrectionPrompt.forTone(DefaultTonePresets.ELABORATE, text, fastMode = false).enforceNoThink
    )
  }

  @Test
  fun polishStillFollowsFastMode() {
    assertTrue(
      CorrectionPrompt.forTone(DefaultTonePresets.POLISH, text, fastMode = true).enforceNoThink
    )
    assertFalse(
      CorrectionPrompt.forTone(DefaultTonePresets.POLISH, text, fastMode = false).enforceNoThink
    )
  }

  /** A tone the user writes decides for itself whether the model reasons first. */
  @Test
  fun aCustomToneDecidesItsOwnReasoning() {
    val quick = TonePreset(id = "custom-1", name = "Quick", systemPrompt = "Be brief.")

    assertTrue(CorrectionPrompt.forTone(quick, text, fastMode = true).enforceNoThink)
    assertFalse(
      CorrectionPrompt.forTone(quick.copy(usesThinking = true), text, fastMode = true)
        .enforceNoThink
    )
  }

  @Test
  fun aThinkingToneLeavesQwensAssistantTurnOpen() {
    val rendered =
      QwenPromptBuilder.buildPrompt(CorrectionPrompt.forTone(DefaultTonePresets.ELABORATE, text))

    assertTrue(
      rendered.startsWith("<|im_start|>system\n" + DefaultTonePresets.ELABORATE.systemPrompt)
    )
    assertTrue(rendered.contains("<|im_start|>user\n$text<|im_end|>"))
    assertTrue(rendered.endsWith("<|im_start|>assistant\n"))
    assertFalse(rendered.contains(QwenPromptBuilder.EMPTY_THINK))
  }

  @Test
  fun toneExamplesBecomeRealChatTurns() {
    val rendered =
      QwenPromptBuilder.buildPrompt(CorrectionPrompt.forTone(DefaultTonePresets.FORMAL, text))

    DefaultTonePresets.FORMAL.examples.forEach { (input, output) ->
      assertTrue(rendered.contains("<|im_start|>user\n$input<|im_end|>"))
      assertTrue(rendered.contains("<|im_start|>assistant\n$output<|im_end|>"))
    }
  }
}
