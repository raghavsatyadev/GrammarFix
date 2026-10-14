package com.raghavsatyadev.grammarfix.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenPromptBuilderTest {

  @Test
  fun buildPrompt_enforceNoThink_prefillsEmptyThinkBlock() {
    val input = "He go to school yesterday"
    val prompt = QwenPromptBuilder.buildPrompt(input, enforceNoThink = true)

    assertTrue(prompt.startsWith("<|im_start|>system"))
    assertTrue(prompt.endsWith("<|im_start|>assistant\n" + QwenPromptBuilder.EMPTY_THINK))
    assertTrue(prompt.contains(input))
  }

  @Test
  fun buildPrompt_thinkingAllowed_leavesAssistantTurnOpen() {
    val input = "He go to school yesterday"
    val prompt = QwenPromptBuilder.buildPrompt(input, enforceNoThink = false)

    assertTrue(prompt.startsWith("<|im_start|>system"))
    assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    assertFalse(prompt.contains("<think>"))
    assertTrue(prompt.contains(input))
  }

  @Test
  fun buildPrompt_containsSystemInstructions() {
    val prompt = QwenPromptBuilder.buildPrompt("Test input")
    assertTrue(prompt.contains(Prompts.Proofread.SYSTEM))
  }
}
