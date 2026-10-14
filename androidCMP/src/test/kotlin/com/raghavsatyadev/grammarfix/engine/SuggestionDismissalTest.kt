package com.raghavsatyadev.grammarfix.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Dismissing a fix from the Grammarly-style popup (issue #8). */
class SuggestionDismissalTest {

  private class FakeDriver(private val words: Map<String, String>) : LlmDriver {
    override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode): String =
      prompt.userText.trim().split(" ").joinToString(" ") { words[it] ?: it }
  }

  private val engine =
    GrammarEngine(
      InferenceEngineImpl(
        llmDriver = FakeDriver(mapOf("go" to "goes", "becos" to "because", "suger" to "sugar"))
      )
    )

  private val text = "she go to market becos of suger"

  @Test
  fun nothingDismissed_keepsTheModelsOutput() = runBlocking {
    val analysis = engine.analyze(text)

    assertSame(analysis, analysis.withoutDismissed(emptySet(), engine))
  }

  @Test
  fun dismissedFix_isDroppedFromTheSuggestions() = runBlocking {
    val analysis = engine.analyze(text)

    val result = analysis.withoutDismissed(setOf(SuggestionKey("becos", "because")), engine)

    assertEquals(listOf("go", "suger"), result.suggestions.map { it.original })
  }

  /** "Accept all" writes correctedText, so it must not bring back a dismissed fix. */
  @Test
  fun acceptAll_skipsDismissedFix() = runBlocking {
    val analysis = engine.analyze(text)

    val result = analysis.withoutDismissed(setOf(SuggestionKey("becos", "because")), engine)

    assertEquals("she goes to market becos of sugar", result.correctedText)
  }

  @Test
  fun everyFixDismissed_leavesTheTextAsTyped() = runBlocking {
    val analysis = engine.analyze(text)

    val result = analysis.withoutDismissed(analysis.suggestions.map { it.key }.toSet(), engine)

    assertEquals(emptyList<TextSuggestion>(), result.suggestions)
    assertEquals(text, result.correctedText)
  }

  @Test
  fun kind_followsTheDiff() {
    assertEquals(SuggestionKind.ADD, TextSuggestion("", "the").kind)
    assertEquals(SuggestionKind.REMOVE, TextSuggestion("very", "").kind)
    assertEquals(SuggestionKind.REPLACE, TextSuggestion("is", "are").kind)
  }
}
