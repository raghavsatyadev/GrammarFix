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
 * Issue #32: runs [ToneSpikeDataset] through the tone prompts on the device's Qwen3 model and
 * writes every answer and its latency, for `tools/tone-spike/score.py` to score off-device.
 *
 * Each prompt runs twice over the whole set: `_COLD` through the proofreading path, which clears
 * the KV cache and prefills everything, and `_CACHED` through [CorrectionPrompt.reusePrefix], which
 * reuses the saved KV state of the fixed opening. Same prompt, same text, so any difference in the
 * labels is the cache's doing, and the scorer lists every sample where the two disagree.
 * - `FEW_SHOT`: the spike's labels-only prompt ([ToneSpikePrompt]), as measured in #49.
 * - `SHIPPING`: the prompt the app ships ([ToneDetection]): labels and a one-line summary.
 * - `ZERO_SHOT`: only with `-e zeroShot 1`; #49 already measured it.
 *
 * Then a handful of samples go through the shipping Formal rewrite, timed the same way, so "much
 * cheaper than a rewrite" is measured rather than quoted. Pass `-e rewriteBaseline 0` to skip it.
 *
 * Like [GecEvalTest] this measures, it does not assert: a wrong label is the data. Only a model
 * that will not load fails the run. Nothing is recorded to analytics — the driver is used directly
 * and the baseline engine gets no recorder.
 */
@RunWith(AndroidJUnit4::class)
class ToneSpikeTest {

  private val tag = "GRAMMARFIX_TONE_SPIKE"

  /**
   * One pass over the set: a name for the results file, its prompt (for the token count), and how
   * to read one text. [read] returns the raw answer for the file and the labels it names.
   */
  private class Run(
    val name: String,
    val prompt: (String) -> CorrectionPrompt,
    val read: suspend (LlamaCppDriver, String) -> Pair<String, List<String>>,
  )

  /** A generated read: the model writes its answer and [parse] reads the labels out of it. */
  private fun generatedRun(
    name: String,
    prompt: (String) -> CorrectionPrompt,
    parse: (String) -> List<String>,
  ) =
    Run(
      name,
      prompt,
      { driver, text ->
        val raw = driver.generate(prompt(text), EngineMode.QWEN3_GGUF)
        raw to parse(raw)
      },
    )

  /**
   * The scored read ("System One"): each label's probability after `Tone:`, no generation. The raw
   * column holds every label's share of the probability, so the secondary-label threshold can be
   * re-chosen off-device. With [summary] the summary line is generated after, and the latency is
   * the pair's.
   */
  private fun scoredRun(name: String, cached: Boolean, summary: Boolean) =
    Run(
      name,
      { ToneDetection.scoringPrompt(it).copy(reusePrefix = cached) },
      { driver, text ->
        val prompt = ToneDetection.scoringPrompt(text).copy(reusePrefix = cached)
        val scores = driver.scoreChoices(prompt, ToneDetection.SCORE_CHOICES, EngineMode.QWEN3_GGUF)
        val labels = ToneDetection.pickLabels(scores)
        val shares =
          ToneDetection.LABELS.zip(ChoiceScore.normalised(scores))
            .sortedByDescending { it.second }
            .joinToString(" ") { (label, p) -> "$label=${"%.3f".format(p)}" }
        val said =
          if (summary && labels.isNotEmpty()) {
            " | " +
              driver.generate(ToneDetection.summaryPrompt(text, labels), EngineMode.QWEN3_GGUF)
          } else ""
        (shares + said) to labels
      },
    )

  private fun runs(zeroShot: Boolean): List<Run> = buildList {
    add(scoredRun("SCORED_COLD", cached = false, summary = false))
    add(scoredRun("SCORED_CACHED", cached = true, summary = false))
    add(scoredRun("SCORED_SUMMARY_CACHED", cached = true, summary = true))
    if (zeroShot) {
      add(
        generatedRun(
          "ZERO_SHOT_COLD",
          { ToneSpikePrompt.build(it, ToneSpikePrompt.Variant.ZERO_SHOT) },
          ToneSpikePrompt::parse,
        )
      )
    }
    for (cached in listOf(false, true)) {
      add(
        generatedRun(
          "FEW_SHOT_${suffix(cached)}",
          { ToneSpikePrompt.build(it, ToneSpikePrompt.Variant.FEW_SHOT, reusePrefix = cached) },
          ToneSpikePrompt::parse,
        )
      )
    }
    for (cached in listOf(false, true)) {
      add(
        generatedRun(
          "SHIPPING_${suffix(cached)}",
          { ToneDetection.prompt(it, reusePrefix = cached) },
          { ToneDetection.parse(it)?.labels.orEmpty() },
        )
      )
    }
  }

  private fun suffix(cached: Boolean) = if (cached) "CACHED" else "COLD"

  // The run takes minutes; without the hold, the app's idle unload frees the model a minute in.
  @Test fun runTheSampleSetAndWriteResults() = holdModelLoaded { runTheSampleSet() }

  private fun runTheSampleSet() = runBlocking {
    val driver = LlamaCppDriver()
    driver.load(defaultModelPath())
    assertTrue("model failed to load; download it in the app first", driver.isLoaded)
    val window = driver.tokenWindow(EngineMode.QWEN3_GGUF)

    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val args = InstrumentationRegistry.getArguments()
    val baselineCount = args.getString("rewriteBaseline")?.toIntOrNull() ?: 3
    val zeroShot = args.getString("zeroShot") == "1"

    val outDir =
      File(instrumentation.targetContext.getExternalFilesDir(null), "tone-spike").apply {
        mkdirs()
      }

    val rows = StringBuilder()
    rows.append("variant\tid\texpected\tpredicted\traw\tprompt_tokens\tlatency_ms\n")

    // `-e variants SCORED_CACHED,SHIPPING_CACHED` runs only those, to keep a comparison short.
    val only = args.getString("variants")?.split(',')?.map { it.trim() }?.toSet()
    runs(zeroShot)
      .filter { only == null || it.name in only }
      .forEach { run ->
        // One throwaway call first, so no measured sample carries the backend's warm-up or, for a
        // cached run, the one-off cost of building the cache.
        run.read(driver, "Warm-up.")
        Log.i(tag, "${run.name} over ${ToneSpikeDataset.all.size} samples")
        ToneSpikeDataset.all.forEach { sample ->
          val prompt = run.prompt(sample.text)
          val promptTokens =
            window?.countTokens(QwenPromptBuilder.buildPrompt(prompt))?.toString() ?: ""

          val start = System.nanoTime()
          val (raw, predicted) =
            try {
              run.read(driver, sample.text)
            } catch (e: Exception) {
              // One failed sample is recorded as an empty answer rather than costing the rest.
              Log.w(tag, "#${sample.id} failed: ${e.message}")
              "" to emptyList()
            }
          val elapsed = (System.nanoTime() - start) / 1_000_000

          Log.i(
            tag,
            "${run.name} #${sample.id} ${elapsed}ms want=${sample.labels} got=$predicted " +
              "raw=${raw.oneLine()}",
          )
          rows
            .append(run.name)
            .append('\t')
            .append(sample.id)
            .append('\t')
            .append(sample.labels.joinToString(","))
            .append('\t')
            .append(predicted.joinToString(","))
            .append('\t')
            .append(raw.oneLine())
            .append('\t')
            .append(promptTokens)
            .append('\t')
            .append(elapsed)
            .append('\n')
        }
      }
    File(outDir, "results.tsv").writeText(rows.toString())

    // The comparison the issue sets: a tone read has to cost far less than a rewrite does.
    if (baselineCount > 0) {
      val engine = InferenceEngineImpl(llmDriver = driver)
      val baseline = StringBuilder("id\tlatency_ms\n")
      ToneSpikeDataset.all.take(baselineCount).forEach { sample ->
        val start = System.nanoTime()
        engine.generateCorrection(
          sample.text,
          tone = DefaultTonePresets.FORMAL,
          source = CorrectionSource.PLAYGROUND,
        )
        val elapsed = (System.nanoTime() - start) / 1_000_000
        Log.i(tag, "rewrite baseline #${sample.id} ${elapsed}ms")
        baseline.append(sample.id).append('\t').append(elapsed).append('\n')
      }
      File(outDir, "rewrite-baseline.tsv").writeText(baseline.toString())
    }

    Log.i(tag, "wrote ${outDir.absolutePath}")
    driver.unload()
  }

  private fun String.oneLine(): String = replace('\t', ' ').replace('\n', ' ').trim()
}
