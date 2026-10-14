package com.raghavsatyadev.grammarfix.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #15 on the real model: text longer than one request used to come back cut off, and text
 * past the context window was refused outright.
 *
 * Each test runs the request the way the engine built it before the fix — the whole text in one
 * call, capped at the tone's own `maxOutputTokens` — and then the same text through the engine, so
 * the difference is measured on the device rather than argued about.
 */
@RunWith(AndroidJUnit4::class)
class DeviceLongTextTest {

  private val tag = "GRAMMARFIX_LONG_TEXT"

  private val engine: InferenceEngine = InferenceEngineImpl()

  /** The same llama.cpp binding the engine uses; one model sits behind both. */
  private val driver = LlamaCppDriver()

  @Before
  fun loadModel() = runBlocking {
    assertTrue("model failed to load; is it downloaded?", engine.initialize(null))
  }

  private suspend fun window(): TokenWindow = driver.tokenWindow(EngineMode.QWEN3_GGUF)!!

  /**
   * What the engine sent before the fix: the whole text in one request, with the answer capped at
   * the tone's own `maxOutputTokens` however much text it was given.
   */
  private suspend fun oneFixedBudgetRequest(text: String, tone: TonePreset): String =
    driver.generate(CorrectionPrompt.forTone(tone, text, fastMode = true), EngineMode.QWEN3_GGUF)

  private fun endsOnACompleteSentence(text: String): Boolean =
    text.trimEnd().lastOrNull() in listOf('.', '!', '?', '"')

  /**
   * A text that fits the context window but not the 256-token answer cap. The old request reached
   * its cap and stopped mid-sentence; nothing marked it as unfinished, so the truncated text went
   * to the editor as the correction.
   */
  @Test
  fun aLongTextIsRewrittenToTheEndInsteadOfBeingCutOff() = runBlocking {
    val text = LongTexts.MEDIUM
    val inputTokens = window().countTokens(text)
    Log.i(tag, "input: ${text.split(Regex("\\s+")).size} words, $inputTokens tokens")

    val before = oneFixedBudgetRequest(text, DefaultTonePresets.POLISH)
    Log.i(tag, "[BEFORE — one request, 256-token cap]\n$before")

    val after = engine.generateCorrection(text, enforceNoThink = true)
    Log.i(tag, "[AFTER — engine]\n${after.correctedText}")
    Log.i(tag, "[AFTER] success=${after.isSuccess} latency=${after.latencyMs}ms")

    // The reproduction: the old request never reaches the last paragraph.
    assertTrue(
      "the fixed cap was expected to truncate this text, but it finished",
      !before.contains(LongTexts.MEDIUM_LAST_WORD, ignoreCase = true),
    )

    assertTrue("inference failed: ${after.errorMessage}", after.isSuccess)
    assertTrue(
      "the rewrite stops before the end of the text",
      after.correctedText.contains(LongTexts.MEDIUM_LAST_WORD, ignoreCase = true),
    )
    assertTrue(
      "the rewrite ends mid-sentence: ...${after.correctedText.takeLast(60)}",
      endsOnACompleteSentence(after.correctedText),
    )
  }

  /**
   * A text past what the window holds at all. The old request was refused by the native side before
   * a single token was generated, and the user got their text back with the raw error on it.
   */
  @Test
  fun aTextPastTheContextWindowIsSplitInsteadOfRejected() = runBlocking {
    val text = LongTexts.LONG
    val inputTokens = window().countTokens(text)
    Log.i(tag, "input: ${text.split(Regex("\\s+")).size} words, $inputTokens tokens")
    Log.i(tag, "context window: ${window().contextTokens} tokens")

    val refusal = runCatching { oneFixedBudgetRequest(text, DefaultTonePresets.ELABORATE) }
    Log.i(tag, "[BEFORE — one request] ${refusal.exceptionOrNull()?.message ?: "no error"}")

    // The reproduction: the request never ran.
    assertTrue(
      "expected the native side to refuse this request",
      refusal.exceptionOrNull()?.message?.contains("too long") == true,
    )

    val after = engine.generateCorrection(text, enforceNoThink = true)
    Log.i(tag, "[AFTER — engine]\n${after.correctedText}")
    Log.i(tag, "[AFTER] success=${after.isSuccess} latency=${after.latencyMs}ms")

    assertTrue("inference failed: ${after.errorMessage}", after.isSuccess)
    assertTrue(
      "the rewrite stops before the end of the text",
      after.correctedText.contains(LongTexts.LONG_LAST_WORD, ignoreCase = true),
    )
    assertTrue(
      "the rewrite ends mid-sentence: ...${after.correctedText.takeLast(60)}",
      endsOnACompleteSentence(after.correctedText),
    )
    // Proofreading keeps what it was given; a chunk lost along the way would show up here.
    val inputWords = after.originalText.split(Regex("\\s+")).size
    val outputWords = after.correctedText.split(Regex("\\s+")).size
    assertTrue(
      "the rewrite lost most of the text: $inputWords words in, $outputWords out",
      outputWords > inputWords * 0.7,
    )
  }

  /**
   * Runs [tone] over [text] and returns the result, having first checked that this text really is
   * more than one request for this tone — every tone gets a different slice of the window, so a
   * text that splits under Elaborate may go through Polish whole.
   */
  private suspend fun runChunked(tone: TonePreset, text: String): CorrectionResult {
    val prompt = CorrectionPrompt.forTone(tone, text, fastMode = true)
    val plan = ContextBudget.plan(prompt, window())
    val chunks = TextChunker.split(text, plan.maxInputTokens, window()::countTokens).chunks

    Log.i(tag, "=== ${tone.name} ===")
    Log.i(
      tag,
      "input=${window().countTokens(text)} tok · prompt=${plan.overheadTokens} tok · " +
        "chunk budget=${plan.maxInputTokens} tok · chunks=${chunks.size}",
    )
    assertTrue(
      "${tone.name} takes this text in one request; it proves nothing about splitting",
      chunks.size > 1,
    )

    val before = runCatching { oneFixedBudgetRequest(text, tone) }
    Log.i(
      tag,
      "[BEFORE — one request, ${tone.maxOutputTokens}-token cap] " +
        (before.exceptionOrNull()?.message
          ?: "ends cleanly=" +
            endsOnACompleteSentence(before.getOrDefault("")) +
            "\n" +
            before.getOrDefault("")),
    )

    val after = engine.generateCorrection(text, enforceNoThink = true, tone = tone)
    Log.i(tag, "[AFTER — engine]\n${after.correctedText}")
    Log.i(
      tag,
      "[AFTER] success=${after.isSuccess} latency=${after.latencyMs}ms " +
        "words ${after.inputWordCount} -> ${after.outputWordCount}",
    )

    assertTrue("${tone.name} failed: ${after.errorMessage}", after.isSuccess)
    assertTrue("${tone.name} returned nothing", after.correctedText.isNotBlank())
    assertTrue(
      "${tone.name} ends mid-sentence: ...${after.correctedText.takeLast(60)}",
      endsOnACompleteSentence(after.correctedText),
    )
    return after
  }

  /**
   * Formal keeps the facts it was given, so both chunks have to come back.
   *
   * Word count is the wrong measure here: a rewrite tone is free to say the same thing in fewer
   * words, and on a long chunk this one condenses hard. What a dropped chunk would look like is the
   * *end* of the text going missing, so that is what is checked.
   */
  @Test
  fun formalRewritesEveryChunk() = runBlocking {
    val result = runChunked(DefaultTonePresets.FORMAL, LongTexts.MEDIUM_LONG)

    assertTrue(
      "Formal lost the first chunk",
      result.correctedText.contains(LongTexts.FIRST_CHUNK_MARKER, ignoreCase = true),
    )
    assertTrue(
      "Formal lost the last chunk",
      result.correctedText.contains(LongTexts.LAST_CHUNK_MARKER, ignoreCase = true),
    )
  }

  /** Shorten condenses, so the test is that it finishes — not that it gives the words back. */
  @Test
  fun shortenCondensesEveryChunk() = runBlocking {
    val result = runChunked(DefaultTonePresets.SHORTEN, LongTexts.MEDIUM_LONG)

    assertTrue(
      "Shorten did not shorten: ${result.inputWordCount} in, ${result.outputWordCount} out",
      result.outputWordCount < result.inputWordCount,
    )
  }

  /**
   * Elaborate is the tone the context limit hit hardest: it asks for two to three times what it was
   * given, out of a budget that also has to hold its reasoning.
   */
  @Test
  fun elaborateExpandsEveryChunk() = runBlocking {
    val result = runChunked(DefaultTonePresets.ELABORATE, LongTexts.MEDIUM)

    assertTrue(
      "Elaborate did not expand: ${result.inputWordCount} in, ${result.outputWordCount} out",
      result.outputWordCount > result.inputWordCount,
    )
  }

  /**
   * A tone the user wrote themselves. Nothing declares what one of these does to the length, so it
   * is budgeted as if it expands; this is the only path where that assumption is exercised.
   *
   * The tone is created through the real repository, against an in-memory store, so the settings
   * file on the device is left alone.
   */
  @Test
  fun aUserDefinedToneIsSplitTheSameWay() = runBlocking {
    val store =
      object : TonePresetStore {
        private var saved = TonePresetSettings()

        override suspend fun load() = saved

        override suspend fun save(settings: TonePresetSettings) {
          saved = settings
        }
      }
    val plainEnglish =
      TonePresetRepository(store)
        .addCustom(
          name = "Plain English",
          systemPrompt =
            "You are an editor who rewrites corporate writing so anyone can follow it.\n" +
              "Task: Rewrite the user's text in plain, direct English.\n" +
              "Instructions:\n" +
              "1. Use everyday words; replace jargon and abbreviations with what they mean.\n" +
              "2. Break long sentences into short ones, one idea each.\n" +
              "3. Keep every fact; add nothing the text does not state.\n" +
              "4. Fix every spelling, grammar and punctuation error along the way.\n" +
              "5. Output ONLY the rewritten text, without markdown, quotes or commentary.",
        )

    assertTrue("a custom tone should be budgeted as expanding", plainEnglish.outputGrowth > 1f)

    val result = runChunked(plainEnglish, LongTexts.MEDIUM_LONG)

    assertTrue(
      "the custom tone lost the first chunk",
      result.correctedText.contains(LongTexts.FIRST_CHUNK_MARKER, ignoreCase = true),
    )
    assertTrue(
      "the custom tone lost the last chunk",
      result.correctedText.contains(LongTexts.LAST_CHUNK_MARKER, ignoreCase = true),
    )
  }
}

/** Deliberately error-ridden prose, long enough to run past the budgets the engine works with. */
private object LongTexts {

  private val PARAGRAPHS =
    listOf(
      "hi team i wanted to give you a quick update on the migration work that we has been doing " +
        "over the last two weeks, their is a lot to cover so please read it all the way to the end.",
      "the first thing we finish was the database move. we ran the export on tuesday night and it " +
        "took almost six hour, which is longer then we expected but still inside the window we agreed.",
      "unfortunately the indexes did not came across cleanly. about fourteen of them had to be " +
        "rebuild by hand on wednesday morning and that is why the reports was slow for most of that day.",
      "on thursday we turn our attention to the api layer. every endpoint had to be point at the " +
        "new host and we found three place where the old address was hard coded in config files " +
        "nobody had touch in years.",
      "the staging enviroment is now fully on the new stack and it have been stable since friday " +
        "afternoon. we seen no errors in the logs apart from the usual noise from the health checks.",
      "performance is better then before. the median response time drop from four hundred " +
        "millisecond to about two hundred and thirty, and the slowest queries are no longer timing " +
        "out during the evening peak.",
      "there is still two open issues. the first is that the nightly backup job have not been " +
        "rescheduled yet, and the second is that the monitoring dashboard still point at the old database.",
      "i would like to close both of them before we do the production cutover. neither is hard but " +
        "they both need someone who have access to the ops account, which at the moment is only priya.",
      "if everything goes to plan we will do the production move next tuesday evening, starting at " +
        "nine o clock and finishing before midnight. we will send a notice to all the customer on " +
        "monday morning.",
      "please reply by friday if you sees any reason we should wait another week. the kiosk team in " +
        "particular should confirm that their release is not landing on the same night.",
      "a few people has asked about the rollback plan so let me set it out here. we keep the old " +
        "database running in read only mode for seven days after the cutover, which give us a way " +
        "back if something serious appear.",
      "rolling back is not free though. any order that was placed after the cutover would have to " +
        "be replayed by hand, so we only do it if the alternative is a outage that last more than a hour.",
      "on the topic of orders, the finance team want a reconciliation report on the wednesday " +
        "morning. dev has wrote the query already but it have not been reviewed by anyone else yet.",
      "we also need to decide what to do with the old reporting server. it cost us about nine " +
        "hundred pound a month and nothing have connected to it since august, but nobody want to be " +
        "the one who turns it off.",
      "my suggestion is that we shut it down two weeks after the cutover and keep a snapshot for " +
        "six month. if no one complains in that time we delete the snapshot and close the account.",
      "the documentation is in a poor state. half the runbooks still describe the old architecture " +
        "and two of them reference a server that was decommission last year, which is not helpful " +
        "at three in the morning.",
      "i have ask marcus to spend a day next week going through them. he do not need to rewrite " +
        "everything, just mark clearly which pages is current and which one are historical.",
      "one more thing about access. we have four people who can log into production and only two of " +
        "them is on call, which mean that a incident at the weekend can sit waiting for someone to " +
        "wake up.",
      "i would like to add two more names to that list before the cutover. the training take about " +
        "half a day and priya has offer to run it on monday if there is enough interest.",
      "the load test results came back on friday and they was better than the last round. we push " +
        "four hundred request a second for twenty minutes and nothing fell over, which is twice " +
        "what we manage in june.",
      "the one weak spot is the search endpoint. it start to queue at about three hundred request " +
        "a second and the latency climb sharply after that, so we should treat it as the next " +
        "thing to look at.",
      "james think the problem is the way we build the query rather than the index itself. he is " +
        "going to spend thursday on it and will bring some numbers to the standup on friday.",
      "we have also been asked whether the migration change anything for the mobile clients. the " +
        "short answer is no, the api contract is identical and no app release is needed.",
      "the longer answer is that the error messages have change slightly in wording. nothing " +
        "depend on the exact text as far as we know, but if you have a integration that match on " +
        "strings please check it.",
      "on cost, the new stack is about fifteen percent cheaper a month once the old reporting " +
        "server goes. most of that come from the smaller instance size we can now get away with.",
      "we did have one scare on saturday when the replica fell behind by nearly forty minutes. it " +
        "caught up on it own after the batch job finish, but we has added a alert so we hear about " +
        "it sooner next time.",
      "the alert go to the same channel as everything else at the moment. if that turn out to be " +
        "too noisy we can move the low priority ones somewhere quieter, but lets see how it feel " +
        "for a week first.",
      "a small thing, but worth saying: the deploy pipeline now take eleven minutes instead of " +
        "twenty six. nobody ask for that, it just fell out of the smaller images, and it have made " +
        "the day noticeably better.",
      "on testing, we now run the full integration suite against a copy of production data every " +
        "night. it catch two issues last week that the old suite would have miss entirely.",
      "i realise this is a lot of detail for one email. if you only read one section make it the " +
        "one about the rollback window, because that is the part that affect everybody.",
      "the support team have already been briefed and they will have a script ready for the common " +
        "questions. anna is writing it today and will share it in the channel tomorrow.",
      "we have not decided yet whether to keep the maintenance page up for the whole window or only " +
        "while the database is switching over. i lean towards the whole window so nobody is surprised.",
      "if the cutover goes well i would like to do a short retrospective on the friday. thirty " +
        "minutes, no slides, just what went right and what we would do different next time.",
      "that is everything for now. thank you for reading this far, and apologies for the length, " +
        "but i would rather over explain then have someone find out about the rollback window from " +
        "a customer.",
    )

  /** Past the 256-token answer cap, inside the context window. Past Elaborate's chunk too. */
  val MEDIUM = PARAGRAPHS.take(10).joinToString("\n\n")

  /** Past the chunk Formal and Shorten get, without making their runs take all afternoon. */
  val MEDIUM_LONG = PARAGRAPHS.take(17).joinToString("\n\n")

  /** Past the context window itself once a tone's answer budget is reserved up front. */
  val LONG = PARAGRAPHS.joinToString("\n\n")

  /** A word only the last paragraph has, so a truncated rewrite cannot contain it. */
  const val MEDIUM_LAST_WORD = "kiosk"

  /** Names from the opening and closing paragraphs of [MEDIUM_LONG], one per chunk. */
  const val FIRST_CHUNK_MARKER = "migration"

  const val LAST_CHUNK_MARKER = "marcus"

  const val LONG_LAST_WORD = "apologies"
}
