package com.raghavsatyadev.grammarfix.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The idle unload frees the model through [InferenceEngine.releaseIfIdle]. It must never free it
 * under a running correction, and a correction afterwards must load it back.
 */
class ReleaseIfIdleTest {

  private class FakeDriver : LlmDriver {
    var loads = 0
    var unloads = 0
    var loaded = false
    /** When set, [generate] waits for it: a correction held mid-flight. */
    var gate: CompletableDeferred<Unit>? = null
    val started = CompletableDeferred<Unit>()

    override val isLoaded: Boolean
      get() = loaded

    override suspend fun load(modelPath: String) {
      loads++
      loaded = true
    }

    override fun unload() {
      unloads++
      loaded = false
    }

    override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode): String {
      check(engineMode != EngineMode.QWEN3_GGUF || loaded) { "generated with no model loaded" }
      started.complete(Unit)
      gate?.await()
      return prompt.userText
    }
  }

  private val driver = FakeDriver()
  private val engine = InferenceEngineImpl(driver, modelPathProvider = { "/m.gguf" })

  @Test
  fun freesAnIdleModelAndTheNextCorrectionLoadsItBack() = runBlocking {
    engine.generateCorrection("one sentence.")
    assertEquals(1, driver.loads)

    assertTrue(engine.releaseIfIdle())
    assertFalse(driver.isLoaded)

    val result = engine.generateCorrection("another sentence.")
    assertTrue(result.isSuccess)
    assertEquals("reloaded lazily", 2, driver.loads)
  }

  @Test
  fun neverFreesTheModelUnderARunningCorrection() = runBlocking {
    driver.gate = CompletableDeferred()
    val running = async { engine.generateCorrection("held mid-flight.") }
    driver.started.await()

    assertFalse(engine.releaseIfIdle())
    assertEquals(0, driver.unloads)

    driver.gate!!.complete(Unit)
    assertTrue(running.await().isSuccess)
    assertEquals(0, driver.unloads)
  }

  @Test
  fun activityCountsRunningAndFinishedCorrections() = runBlocking {
    assertEquals(InferenceActivity(running = 0, finished = 0), engine.activity.value)
    driver.gate = CompletableDeferred()
    val running = async { engine.generateCorrection("counted.") }
    driver.started.await()
    assertEquals(InferenceActivity(running = 1, finished = 0), engine.activity.value)

    driver.gate!!.complete(Unit)
    running.await()
    assertEquals(InferenceActivity(running = 0, finished = 1), engine.activity.value)
  }

  @Test
  fun aFailedCorrectionStillCountsAsFinished() = runBlocking {
    val failing =
      object : LlmDriver {
        override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode): String =
          throw IllegalStateException("boom")
      }
    val engine = InferenceEngineImpl(failing, modelPathProvider = { "/m.gguf" })

    assertFalse(engine.generateCorrection("fails.").isSuccess)
    assertEquals(InferenceActivity(running = 0, finished = 1), engine.activity.value)
  }
}
