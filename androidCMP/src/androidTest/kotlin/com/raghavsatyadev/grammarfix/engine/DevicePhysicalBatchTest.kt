package com.raghavsatyadev.grammarfix.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DevicePhysicalBatchTest {

  private val TAG = "GRAMMARFIX_PHYSICAL_BENCHMARK"

  private val testSentences = BenchmarkSentences.all

  @Test
  fun executePhysical10SentenceBenchmarkOnDevice() {
    runBlocking {
      // The real llama.cpp driver, loading the model the app downloaded. Download it first.
      val engine: InferenceEngine = InferenceEngineImpl()
      val isInitialized = engine.initialize(null)
      assertTrue("model failed to load; is it downloaded?", isInitialized)
      val outputs = mutableListOf<String>()

      Log.i(TAG, "=================================================================")
      Log.i(TAG, "STARTING 10-SENTENCE PHYSICAL DEVICE BENCHMARK ON GALAXY S25 ULTRA")
      Log.i(TAG, "=================================================================")

      testSentences.forEachIndexed { index, sentence ->
        val startTime = System.currentTimeMillis()
        val result = engine.generateCorrection(sentence, enforceNoThink = true)
        val durationMs = System.currentTimeMillis() - startTime
        val currentRamMb = getCurrentRamUsageMb()

        Log.i(TAG, "-----------------------------------------------------------------")
        Log.i(TAG, "TEST CASE #${index + 1}:")
        Log.i(TAG, "[INPUT]:\n$sentence")
        Log.i(TAG, "[CORRECTED OUTPUT]:\n${result.correctedText}")
        Log.i(TAG, "[DIFFS DETECTED]: ${result.diffs.size} tokens modified")
        result.diffs.forEach { diff ->
          if (diff.type != DiffType.UNCHANGED) {
            Log.i(TAG, "  -> [${diff.type}]: '${diff.originalText}' → '${diff.correctedText}'")
          }
        }
        Log.i(
          TAG,
          "[METRICS]: Latency: ${durationMs}ms | TPS: ${result.tokensPerSecond} | RAM: ${currentRamMb}MB",
        )
        Log.i(TAG, "-----------------------------------------------------------------")

        assertTrue("inference failed: ${result.errorMessage}", result.isSuccess)
        assertTrue(
          "Output should not be blank for sentence #${index + 1}",
          result.correctedText.isNotBlank(),
        )
        assertTrue(
          "model changed nothing in sentence #${index + 1}",
          result.diffs.any {
            it.type != DiffType.UNCHANGED
          },
        )
        outputs += result.correctedText
      }

      // The second-inference regression check. Decoding is greedy, so the same prompt must give
      // the same text. Any KV-cache or sampler state left over from the nine runs in between
      // would change it.
      val again = engine.generateCorrection(testSentences[0], enforceNoThink = true)
      Log.i(TAG, "[REPEAT #1 AFTER 10 RUNS]: ${again.correctedText}")
      assertEquals("state leaked between inferences", outputs[0], again.correctedText)

      Log.i(TAG, "=================================================================")
      Log.i(TAG, "COMPLETED ALL 10 PHYSICAL DEVICE BENCHMARK RUNS SUCCESSFULLY")
      Log.i(TAG, "=================================================================")
    }
  }
}
