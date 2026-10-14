package com.raghavsatyadev.grammarfix.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay flow from issue #3: accept fixes one by one, clear the field, type a second sentence,
 * then accept all.
 */
class OverlaySuggestionFlowTest {

  /** Test double: fixes known words, or returns a canned output for an exact input. */
  private class FakeDriver(
    private val words: Map<String, String>,
    private val canned: Map<String, String> = emptyMap(),
  ) : LlmDriver {
    override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode): String {
      val user = prompt.userText.trim()
      canned[user]?.let {
        return it
      }
      return user.split(" ").joinToString(" ") { words[it] ?: it }
    }
  }

  private val fixes =
    mapOf(
      "go" to "goes",
      "becos" to "because",
      "need" to "needs",
      "suger" to "sugar",
      "was" to "were",
      "comming" to "coming",
      "tommorrow" to "tomorrow",
    )

  private fun engine(canned: Map<String, String> = emptyMap()) =
    GrammarEngine(InferenceEngineImpl(llmDriver = FakeDriver(fixes, canned)))

  private val first = "she go to market becos she need suger"
  private val second = "they was comming tommorrow"

  @Test
  fun suggestions_carryExactCharacterRanges() = runBlocking {
    val analysis = engine().analyze(first)

    val go = analysis.suggestions.first()
    assertEquals("go", go.original)
    assertEquals("go", first.substring(go.startIndex, go.endIndex))
  }

  @Test
  fun applyingOneSuggestion_changesOnlyThatWord() = runBlocking {
    val engine = engine()
    val analysis = engine.analyze(first)

    val result = engine.applySuggestionAt(first, analysis.suggestions.first())

    assertEquals("she goes to market becos she need suger", result)
  }

  /** Before the fix, Apply always wrote the model's entire output. */
  @Test
  fun acceptingOneByOne_appliesExactlyOneFixPerStep() = runBlocking {
    val engine = engine()
    var text = first
    var steps = 0

    while (true) {
      val analysis = engine.analyze(text)
      if (analysis.suggestions.isEmpty()) break
      val before = analysis.suggestions.size
      text = engine.applySuggestionAt(text, analysis.suggestions.first())!!
      assertEquals("one fix per step", before - 1, engine.analyze(text).suggestions.size)
      steps++
    }

    assertEquals(4, steps)
    assertEquals("she goes to market because she needs sugar", text)
  }

  @Test
  fun secondSentenceAfterClearing_isAnalysedIndependently() = runBlocking {
    val engine = engine()

    val firstAnalysis = engine.analyze(first)
    engine.applySuggestionAt(first, firstAnalysis.suggestions.first())
    assertTrue(engine.analyze("").suggestions.isEmpty())

    val afterFirst = engine.analyze(second)
    val fresh = engine().analyze(second)

    assertEquals(fresh, afterFirst)
    assertEquals(listOf("was", "comming", "tommorrow"), afterFirst.suggestions.map { it.original })
  }

  @Test
  fun acceptAll_resolvesEverySuggestionInOneAction() = runBlocking {
    val engine = engine()
    val analysis = engine.analyze(second)

    assertEquals(3, analysis.suggestions.size)
    assertEquals("they were coming tomorrow", analysis.correctedText)
    assertTrue(engine.analyze(analysis.correctedText).suggestions.isEmpty())
  }

  /** A suggestion computed for old text must not be spliced into new text. */
  @Test
  fun staleSuggestion_isRejected() = runBlocking {
    val engine = engine()
    val stale = engine.analyze(first).suggestions.first()

    assertNull(engine.applySuggestionAt("completely different text now", stale))
    assertNull(engine.applySuggestionAt("", stale))
  }

  @Test
  fun deletionAndInsertion_keepSingleSpacing() = runBlocking {
    val engine =
      engine(
        canned =
          mapOf(
            "it is is fine" to "it is fine",
            "go to market" to "go to the market",
          )
      )

    val deletion = engine.analyze("it is is fine").suggestions.single()
    assertEquals("it is fine", engine.applySuggestionAt("it is is fine", deletion))

    val insertion = engine.analyze("go to market").suggestions.single()
    assertEquals("go to the market", engine.applySuggestionAt("go to market", insertion))
  }

  /**
   * The overlay cannot use an answer to a question the user has moved on from.
   *
   * The check runs inside the engine, the moment the model returns, and its verdict comes back on
   * the analysis — so the suggestions the service refuses to show and the row analytics refuses to
   * count as a correction are decided by the same answer (#34).
   */
  @Test
  fun anAnalysisTheFieldHasMovedPastIsMarkedStale() = runBlocking {
    val engine = engine()

    assertTrue(engine.analyze(first) { false }.isStale)
    assertFalse(engine.analyze(first).isStale)
  }
}
