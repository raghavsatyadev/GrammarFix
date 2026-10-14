package com.raghavsatyadev.grammarfix.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tone read's prompt and its answer parsing (#32). The parser only checks format and the closed
 * vocabulary; the labels themselves always come from the model.
 */
class ToneDetectionTest {

  @Test
  fun readsLabelsStrongestFirstAndTheSummary() {
    val reading =
      ToneDetection.parse("Tone: direct, frustrated\nSummary: A blunt refusal from someone fed up.")

    assertEquals(listOf("direct", "frustrated"), reading?.labels)
    assertEquals("A blunt refusal from someone fed up.", reading?.summary)
  }

  @Test
  fun dropsWordsOutsideTheVocabularyRatherThanMappingThem() {
    // "angry" is not a label; it must not be turned into "frustrated" or anything else.
    val reading = ToneDetection.parse("Tone: angry, Urgent., urgent\nSummary: Needs it now.")

    assertEquals(listOf("urgent"), reading?.labels)
  }

  @Test
  fun anAnswerWithNoValidLabelIsNoReading() {
    assertNull(ToneDetection.parse("Tone: angry\nSummary: Cross."))
    assertNull(ToneDetection.parse("Sure! Here is a reply to your message."))
    assertNull(ToneDetection.parse(""))
  }

  @Test
  fun keepsAtMostThreeLabels() {
    val reading = ToneDetection.parse("Tone: formal, casual, friendly, direct, urgent")

    assertEquals(listOf("formal", "casual", "friendly"), reading?.labels)
  }

  @Test
  fun aMissingOrRunawaySummaryLeavesTheLabelsStanding() {
    assertEquals("", ToneDetection.parse("Tone: casual")?.summary)
    val long = "x".repeat(ToneDetection.MAX_SUMMARY_CHARS + 1)
    val reading = ToneDetection.parse("Tone: casual\nSummary: $long")
    assertEquals(listOf("casual"), reading?.labels)
    assertEquals("", reading?.summary)
  }

  @Test
  fun ignoresAReasoningBlockAndTheEndOfTurnMarker() {
    val reading =
      ToneDetection.parse(
        "<think>\nTone: formal\n</think>\nTone: apologetic\nSummary: Sorry.<|im_end|>junk"
      )

    assertEquals(listOf("apologetic"), reading?.labels)
    assertEquals("Sorry.", reading?.summary)
  }

  @Test
  fun thePromptAsksForTheCachedPathAndFramesTheMessage() {
    val prompt = ToneDetection.prompt("hey u free later?")

    assertTrue(prompt.reusePrefix)
    assertTrue(prompt.enforceNoThink)
    assertEquals(ToneDetection.MAX_OUTPUT_TOKENS, prompt.maxOutputTokens)
    assertEquals("Message: hey u free later?", prompt.userText)
    assertFalse(ToneDetection.prompt("x", reusePrefix = false).reusePrefix)
  }

  /** What the native cache is keyed on must be a true prefix, and must hold none of the text. */
  @Test
  fun theFixedOpeningIsAPrefixOfTheFullPromptAndCarriesNoUserText() {
    val text = "Server is down, join the call now!"
    val prompt = ToneDetection.prompt(text)
    val opening = QwenPromptBuilder.fixedOpening(prompt)
    val full = QwenPromptBuilder.buildPrompt(prompt)

    assertTrue(full.startsWith(opening))
    assertFalse(opening.contains(text))
    assertEquals(opening, QwenPromptBuilder.fixedOpening(ToneDetection.prompt("other text")))
  }

  @Test
  fun proofreadingNeverAsksForTheCache() {
    assertFalse(CorrectionPrompt.forText("he go to school").reusePrefix)
    DefaultTonePresets.all.forEach { assertFalse(CorrectionPrompt.forTone(it, "x").reusePrefix) }
  }

  @Test
  fun everyWorkedExampleAnswersInTheFormatItAsksFor() {
    Prompts.ToneRead.EXAMPLES.forEach { (message, answer) ->
      assertTrue(message.startsWith(Prompts.ToneRead.MESSAGE_PREFIX))
      val reading = ToneDetection.parse(answer)
      assertTrue(answer, reading != null && reading.summary.isNotEmpty())
    }
  }

  /** Log-probabilities in [ToneDetection.LABELS] order, from shares that need not sum to one. */
  private fun scores(vararg share: Pair<String, Double>): List<ChoiceScore> =
    ToneDetection.LABELS.map { label ->
      val p = share.toMap()[label]
      ChoiceScore(if (p == null) -30f else kotlin.math.ln(p).toFloat(), tokens = 1)
    }

  @Test
  fun scoredReadKeepsTheTopLabelAndOnlyStrongRunnersUp() {
    val picked =
      ToneDetection.pickLabels(
        scores("direct" to 0.5, "frustrated" to 0.3, "formal" to 0.1, "urgent" to 0.1),
        minShare = 0.2f,
      )
    assertEquals(listOf("direct", "frustrated"), picked)
  }

  @Test
  fun scoredReadKeepsTheTopLabelEvenWhenItIsWeakAndAtMostThree() {
    assertEquals(
      listOf("casual"),
      // No label reaches the threshold: the model spread its answer thin. The top one still stands.
      ToneDetection.pickLabels(
        scores(
          *ToneDetection.LABELS.map { it to if (it == "casual") 0.16 else 0.12 }.toTypedArray()
        ),
        minShare = 0.2f,
      ),
    )
    val even = scores(*ToneDetection.LABELS.map { it to 1.0 }.toTypedArray())
    assertEquals(3, ToneDetection.pickLabels(even, minShare = 0.1f).size)
  }

  @Test
  fun scoredReadWithoutUsableScoresIsNoReading() {
    assertTrue(ToneDetection.pickLabels(emptyList()).isEmpty())
    assertTrue(
      ToneDetection.pickLabels(ToneDetection.LABELS.map { ChoiceScore(Float.NEGATIVE_INFINITY, 0) })
        .isEmpty()
    )
  }

  @Test
  fun scoringAndSummaryPromptsShareTheReadsCachedOpening() {
    val read = ToneDetection.prompt("See you at six")
    val scoring = ToneDetection.scoringPrompt("See you at six")
    val summary = ToneDetection.summaryPrompt("See you at six", listOf("casual", "friendly"))

    val opening = QwenPromptBuilder.fixedOpening(read)
    assertEquals(opening, QwenPromptBuilder.fixedOpening(scoring))
    assertEquals(opening, QwenPromptBuilder.fixedOpening(summary))
    assertTrue(scoring.reusePrefix && summary.reusePrefix)
    assertTrue(
      QwenPromptBuilder.buildPrompt(scoring).endsWith(QwenPromptBuilder.EMPTY_THINK + "Tone:")
    )
    assertTrue(QwenPromptBuilder.buildPrompt(summary).endsWith("Tone: casual, friendly\nSummary:"))
    assertTrue(ToneDetection.SCORE_CHOICES.all { it.startsWith(" ") })
  }

  @Test
  fun summaryIsItsFirstLineOrNothing() {
    assertEquals(
      "A warm plan to meet.",
      ToneDetection.parseSummary(" A warm plan to meet.\nTone: x"),
    )
    assertEquals("", ToneDetection.parseSummary("x".repeat(ToneDetection.MAX_SUMMARY_CHARS + 1)))
  }

  @Test
  fun proofreadingPromptsHaveNoAnswerPrefix() {
    assertTrue(QwenPromptBuilder.buildPrompt("i has a dog").endsWith(QwenPromptBuilder.EMPTY_THINK))
  }

  @Test
  fun storedLabelsKeepOnlyTheVocabulary() {
    assertEquals("direct,urgent", ToneDetection.encodeLabels(listOf("direct", "my text", "urgent")))
    assertEquals(listOf("formal"), ToneDetection.decodeLabels("formal,hello"))
  }
}
