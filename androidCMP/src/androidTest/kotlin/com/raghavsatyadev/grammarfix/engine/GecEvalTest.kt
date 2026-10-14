package com.raghavsatyadev.grammarfix.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #41: runs [GecEvalDataset] through the engine exactly as it ships and writes what came
 * back, so it can be scored off-device by `tools/gec-eval/score.py`.
 *
 * This measures, it does not assert. The only thing that fails the run is the model not loading or
 * inference erroring — a poor correction is the result we are here to collect, not a test failure.
 * Scoring lives in the Python script so the numbers can be recomputed without rebuilding an APK.
 *
 * Nothing here touches the analytics table: [InferenceEngineImpl] is constructed directly, and the
 * recorder is wired in above it, so a fifty-sentence run does not pollute the user's statistics.
 *
 * Takes several minutes. Roughly 5-8 s per sentence on a Pixel 10 Pro, less on an S25 Ultra.
 */
@RunWith(AndroidJUnit4::class)
class GecEvalTest {

  private val tag = "GRAMMARFIX_GEC_EVAL"

  // The run takes minutes; without the hold, the app's idle unload frees the model a minute in.
  @Test fun runTheSampleSetAndWriteResults() = holdModelLoaded { runTheSampleSet() }

  private fun runTheSampleSet() = runBlocking {
    val engine: InferenceEngine = InferenceEngineImpl()
    assertTrue("model failed to load; download it in the app first", engine.initialize(null))

    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val outDir = File(context.getExternalFilesDir(null), "gec-eval").apply { mkdirs() }
    val outFile = File(outDir, "results.tsv")

    val rows = StringBuilder()
    rows.append("id\tcategory\tinput\texpected\tactual\tlatency_ms\n")

    Log.i(tag, "running ${GecEvalDataset.all.size} samples (${GecEvalDataset.clean.size} clean)")

    GecEvalDataset.all.forEach { sample ->
      val start = System.currentTimeMillis()
      val result = engine.generateCorrection(sample.input, enforceNoThink = true)
      val elapsed = System.currentTimeMillis() - start

      // A failed inference is recorded as an empty correction rather than aborting the run: one
      // bad sample should not cost the other forty-nine.
      val actual = if (result.isSuccess) result.correctedText else ""
      if (!result.isSuccess) Log.w(tag, "#${sample.id} failed: ${result.errorMessage}")

      Log.i(tag, "#${sample.id} [${sample.category}] ${elapsed}ms")
      Log.i(tag, "  in  : ${sample.input}")
      Log.i(tag, "  want: ${sample.expected}")
      Log.i(tag, "  got : $actual")

      rows
        .append(sample.id)
        .append('\t')
        .append(sample.category)
        .append('\t')
        .append(sample.input.tsvSafe())
        .append('\t')
        .append(sample.expected.tsvSafe())
        .append('\t')
        .append(actual.tsvSafe())
        .append('\t')
        .append(elapsed)
        .append('\n')
    }

    outFile.writeText(rows.toString())
    Log.i(tag, "wrote ${outFile.absolutePath}")

    engine.unloadModel()
  }

  /** Tabs and newlines would break the columns; the model can emit both. */
  private fun String.tsvSafe(): String = replace('\t', ' ').replace('\n', ' ').trim()
}
