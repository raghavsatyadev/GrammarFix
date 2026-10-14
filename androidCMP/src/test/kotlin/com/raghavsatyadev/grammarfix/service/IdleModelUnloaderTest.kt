package com.raghavsatyadev.grammarfix.service

import com.raghavsatyadev.grammarfix.engine.InferenceActivity
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model used to stay loaded for as long as the accessibility service kept the process alive
 * (~2.5 GB resident with the app and the bubble both closed). It is now freed after a minute with
 * neither on screen and nothing running.
 *
 * Virtual time throughout: [advanceTimeBy] moves the clock without waiting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdleModelUnloaderTest {

  private val scope = TestScope()
  private var releases = 0
  private val unloader = IdleModelUnloader(scope) { releases++ }

  /** Moves the clock by [seconds] and runs whatever came due. */
  private fun wait(seconds: Int) {
    scope.advanceTimeBy(seconds.seconds)
    scope.runCurrent()
  }

  /** The app was open and the assistant running, as on a normal first use. */
  private fun inUse() {
    unloader.onAppVisible(true)
    unloader.onAssistantRunning(true)
  }

  private fun correction(finishedSoFar: Long) {
    unloader.onInferenceActivity(InferenceActivity(running = 1, finished = finishedSoFar))
    unloader.onInferenceActivity(InferenceActivity(running = 0, finished = finishedSoFar + 1))
  }

  @Test
  fun theTimeoutIsOneMinute() {
    assertEquals(60.seconds, MODEL_IDLE_TIMEOUT)
  }

  @Test
  fun closingTheAppAndTheAssistantFreesTheModelAfterAMinute() {
    inUse()
    unloader.onAppVisible(false)
    unloader.onAssistantRunning(false)

    wait(59)
    assertEquals("still inside the minute", 0, releases)
    wait(1)
    assertEquals(1, releases)
  }

  @Test
  fun theBubbleAloneKeepsTheModelLoaded() {
    inUse()
    unloader.onAppVisible(false)

    wait(600)
    assertEquals(0, releases)
    assertFalse(unloader.isCountingDown)
  }

  @Test
  fun theAppAloneKeepsTheModelLoaded() {
    inUse()
    unloader.onAssistantRunning(false)

    wait(600)
    assertEquals(0, releases)
  }

  @Test
  fun reopeningTheAppWithinTheMinuteCancelsTheUnload() {
    inUse()
    unloader.onAppVisible(false)
    unloader.onAssistantRunning(false)

    wait(45)
    unloader.onAppVisible(true)
    wait(600)
    assertEquals(0, releases)
  }

  @Test
  fun startingTheAssistantWithinTheMinuteCancelsTheUnload() {
    unloader.onAppVisible(false)
    unloader.onAssistantRunning(false)

    wait(30)
    unloader.onAssistantRunning(true)
    wait(600)
    assertEquals(0, releases)
  }

  /** The accessibility service corrects text even with the bubble closed; that is use too. */
  @Test
  fun aCorrectionWhileIdleRestartsTheMinuteFromWhenItFinished() {
    unloader.onAppVisible(false)
    unloader.onAssistantRunning(false)

    wait(40)
    unloader.onInferenceActivity(InferenceActivity(running = 1, finished = 0))
    wait(100) // a long correction: nothing may be freed while it runs
    assertEquals(0, releases)
    unloader.onInferenceActivity(InferenceActivity(running = 0, finished = 1))

    wait(59)
    assertEquals("the minute counts from the end of the correction", 0, releases)
    wait(1)
    assertEquals(1, releases)
  }

  /**
   * A conflated flow can hide the "running" value of a quick correction; the finished count still
   * shows it happened.
   */
  @Test
  fun aCorrectionSeenOnlyByItsFinishedCountStillRestartsTheMinute() {
    unloader.onAppVisible(false)
    unloader.onAssistantRunning(false)

    wait(50)
    unloader.onInferenceActivity(InferenceActivity(running = 0, finished = 1))
    wait(50)
    assertEquals(0, releases)
    wait(10)
    assertEquals(1, releases)
  }

  @Test
  fun theLaterOfBothGoneAndLastCorrectionStartsTheMinute() {
    inUse()
    correction(finishedSoFar = 0)
    wait(300) // the correction finished long before anything was closed
    unloader.onAppVisible(false)
    unloader.onAssistantRunning(false)

    wait(59)
    assertEquals(0, releases)
    wait(1)
    assertEquals(1, releases)
  }

  @Test
  fun aRepeatedGoneSignalDoesNotPushTheUnloadBack() {
    unloader.onAppVisible(false)
    unloader.onAssistantRunning(false)

    wait(30)
    unloader.onAssistantRunning(false)
    unloader.onAppVisible(false)
    wait(30)
    assertEquals(1, releases)
  }

  @Test
  fun freesOncePerIdleStretchAndAgainAfterTheNextUse() {
    unloader.onAppVisible(false)
    unloader.onAssistantRunning(false)
    wait(600)
    assertEquals("one release, not one a minute", 1, releases)

    // The next correction reloads the model; a minute after it, it is freed again.
    correction(finishedSoFar = 0)
    wait(60)
    assertEquals(2, releases)
    assertFalse(unloader.isCountingDown)
  }

  @Test
  fun isCountingDownOnlyWhileTheMinuteRuns() {
    inUse()
    assertFalse(unloader.isCountingDown)
    unloader.onAppVisible(false)
    unloader.onAssistantRunning(false)
    assertTrue(unloader.isCountingDown)
    unloader.onAppVisible(true)
    assertFalse(unloader.isCountingDown)
  }
}
