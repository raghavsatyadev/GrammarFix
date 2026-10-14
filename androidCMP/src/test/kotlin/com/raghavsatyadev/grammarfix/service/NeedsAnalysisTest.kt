package com.raghavsatyadev.grammarfix.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When an accessibility event is worth running the model for (issue #34).
 *
 * The overlay is woken by text-changed, focus, selection and window events, and most of them are
 * not an edit. Before this rule existed, each one cancelled the run in flight and started another,
 * so two cursor-key presses over an untouched field produced three inferences and three identical
 * rows in the user's statistics.
 */
class NeedsAnalysisTest {

  private val text = "he go to school"

  @Test
  fun textWeHaveNoAnswerForIsAnalysed() {
    assertTrue(needsAnalysis(text, analysed = null, pending = null, isPendingActive = false))
  }

  /**
   * The bug in #34, as a test: a caret move, a tap in the field or the window coming back reports
   * the text we are already showing suggestions for. There is nothing new to say about it.
   */
  @Test
  fun theTextWeAreAlreadyShowingIsNotAnalysedAgain() {
    assertFalse(needsAnalysis(text, analysed = text, pending = text, isPendingActive = false))
  }

  /** A burst of events during typing must not keep restarting the same run. */
  @Test
  fun aRunAlreadyInFlightForTheSameTextIsLeftToFinish() {
    assertFalse(needsAnalysis(text, analysed = null, pending = text, isPendingActive = true))
  }

  /** An actual edit still cancels what is running: the old answer is about to be worthless. */
  @Test
  fun anEditWhileTheModelIsRunningStartsAFreshRun() {
    assertTrue(
      needsAnalysis("he goes to school", analysed = null, pending = text, isPendingActive = true)
    )
  }

  /**
   * A run that was dropped as stale left no analysis behind, so the same text coming back is worth
   * running again — otherwise the field the user is looking at would never get its suggestions.
   */
  @Test
  fun textWhoseRunWasDroppedIsAnalysedAgain() {
    assertTrue(needsAnalysis(text, analysed = null, pending = text, isPendingActive = false))
  }

  /** Clearing the field is an edit like any other; the suggestions for the old text must go. */
  @Test
  fun clearingTheFieldIsAnalysed() {
    assertTrue(needsAnalysis("", analysed = text, pending = text, isPendingActive = false))
  }
}
