package com.raghavsatyadev.grammarfix.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Accepting a fix shifts the fixes left in place instead of running the model again, and a fix
 * scoped to a selection leaves the rest of the text alone.
 */
class AppliedFixesTest {

  private val engine =
    GrammarEngine(
      inferenceEngine =
        InferenceEngineImpl(
          llmDriver =
            object : LlmDriver {
              override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode) = ""
            }
        )
    )

  private val text = "i has a apple and they is here"
  private val has = TextSuggestion("has", "have", 2, 5)
  private val a = TextSuggestion("a", "an", 6, 7)
  private val isFix = TextSuggestion("is", "are", 23, 25)
  private val analysis =
    TextAnalysis(text, "i have an apple and they are here", listOf(has, a, isFix))

  @Test
  fun acceptingOneFixShiftsTheFixesAfterIt() {
    val result = analysis.afterApplying(listOf(has), engine)!!.analysis

    assertEquals("i have a apple and they is here", result.analyzedText)
    assertEquals(
      listOf(a.copy(startIndex = 7, endIndex = 8), isFix.copy(startIndex = 24, endIndex = 26)),
      result.suggestions,
    )
    // The shifted fixes still apply cleanly to the new text.
    assertEquals("i have an apple and they are here", result.correctedText)
  }

  @Test
  fun acceptingSeveralAppliesThemAllInOnePass() {
    val result = analysis.afterApplying(listOf(has, isFix), engine)!!.analysis

    assertEquals("i have a apple and they are here", result.analyzedText)
    assertEquals(listOf(a.copy(startIndex = 7, endIndex = 8)), result.suggestions)
  }

  @Test
  fun aFixThatNoLongerMatchesIsRefused() {
    assertNull(analysis.afterApplying(listOf(has.copy(original = "had")), engine))
  }

  @Test
  fun aSelectionGrowsWithTheFixesInsideIt() {
    val edits = analysis.afterApplying(listOf(a), engine)!!.edits
    // "a apple" selected; "a" becomes "an".
    assertEquals(6 until 14, (6 until 13).shiftedBy(edits))
  }

  @Test
  fun onlyFixesInsideTheSelectionAreInScope() {
    val scope = wordScope(text, 19, 25)
    assertEquals(18 until 25, scope)
    assertEquals(listOf(isFix), analysis.suggestions.filter { it.isWithin(scope) })
  }

  @Test
  fun anEmptySelectionHasNoScope() {
    assertNull(wordScope(text, 4, 4))
  }
}
