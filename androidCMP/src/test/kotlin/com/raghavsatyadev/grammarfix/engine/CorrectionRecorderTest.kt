package com.raghavsatyadev.grammarfix.engine

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.raghavsatyadev.grammarfix.data.CorrectionEventEntity
import com.raghavsatyadev.grammarfix.data.GrammarFixDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The capture hook on the real correction path.
 *
 * The behaviours that matter are negative ones: a Playground run must write nothing, a database
 * error must not cost the user their grammar fix, and nothing the user wrote may reach the file.
 */
class CorrectionRecorderTest {

  private lateinit var directory: File
  private var database: GrammarFixDatabase? = null

  @Before
  fun setUp() {
    directory = File.createTempFile("grammarfix-recorder", "").apply { delete() }
    directory.mkdirs()
  }

  @After
  fun tearDown() {
    database?.close()
    directory.deleteRecursively()
  }

  /** Answers by shouting the text back, so the diff is one `REPLACED` per word. */
  private val shoutingDriver =
    object : LlmDriver {
      override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode): String =
        prompt.userText.uppercase()
    }

  private class Recording : CorrectionRecorder {
    val events = mutableListOf<Pair<CorrectionResult, CorrectionSource>>()

    override suspend fun record(result: CorrectionResult, source: CorrectionSource) {
      events += result to source
    }
  }

  // --- the Playground is excluded ---------------------------------------------------------------

  /**
   * The acceptance criterion this issue turns on.
   *
   * "Run all 10" fires ten canned benchmark sentences. Recording them would swamp real usage in
   * every chart, so they are never written — not written and filtered later, because a filter can
   * be forgotten by a query someone adds in a year's time.
   */
  @Test
  fun aPlaygroundRunIsNotRecorded() = runBlocking {
    val recorder = Recording()
    val engine = InferenceEngineImpl(shoutingDriver, { "/m.gguf" }, recorder)

    val result = engine.generateCorrection("he go to school", source = CorrectionSource.PLAYGROUND)

    assertTrue(recorder.events.isEmpty())
    // The Playground still gets its correction and its timing figures.
    assertEquals("HE GO TO SCHOOL", result.correctedText)
    assertTrue(result.isSuccess)
  }

  /** A failed Playground run is not recorded either — failures are excluded by source, not luck. */
  @Test
  fun aFailedPlaygroundRunIsNotRecorded() = runBlocking {
    val recorder = Recording()
    val engine = InferenceEngineImpl(failingDriver(), { "/m.gguf" }, recorder)

    engine.generateCorrection("he go to school", source = CorrectionSource.PLAYGROUND)

    assertTrue(recorder.events.isEmpty())
  }

  /** Second line of defence: the recorder itself refuses a Playground row, engine or no engine. */
  @Test
  fun theRoomRecorderRefusesAPlaygroundRowOnItsOwn() = runBlocking {
    val database = openDatabase()
    val recorder = RoomCorrectionRecorder(database) { 1_700_000_000_000L }

    recorder.record(successfulResult(), CorrectionSource.PLAYGROUND)

    assertEquals(0, database.correctionEventDao().count())
  }

  // --- real usage is recorded -------------------------------------------------------------------

  /** The overlay is the surface every correction in another app comes through. */
  @Test
  fun anOverlayCorrectionWritesExactlyOneRow() = runBlocking {
    val recorder = Recording()
    val engine = InferenceEngineImpl(shoutingDriver, { "/m.gguf" }, recorder)

    GrammarEngine(engine, engineMode = { EngineMode.QWEN3_GGUF }).analyze("he go to school")

    val (result, source) = recorder.events.single()
    assertEquals(CorrectionSource.OVERLAY, source)
    assertTrue(result.isSuccess)
  }

  /** An empty box runs no inference, so there is nothing to count and no row to write. */
  @Test
  fun blankTextIsNotRecorded() = runBlocking {
    val recorder = Recording()
    val engine = InferenceEngineImpl(shoutingDriver, { "/m.gguf" }, recorder)

    engine.generateCorrection("   ")

    assertTrue(recorder.events.isEmpty())
  }

  /**
   * "How often does inference fail" is a question worth answering, so a failure is a row like any
   * other — and the user still sees the reason in the UI.
   */
  @Test
  fun aFailedInferenceIsRecordedAndStillReportedToTheUser() = runBlocking {
    val recorder = Recording()
    val engine = InferenceEngineImpl(failingDriver(), { "/m.gguf" }, recorder)

    val result = engine.generateCorrection("he go to school")

    val (recorded, _) = recorder.events.single()
    assertFalse(recorded.isSuccess)
    assertEquals(CorrectionErrorCode.INFERENCE_FAILED, recorded.errorCode)
    assertEquals("Model not downloaded yet", result.errorMessage)
    assertEquals("he go to school", result.correctedText)
  }

  /** A tone whose prompt fills the window is its own code, told apart by type, not by message. */
  @Test
  fun aPromptThatFillsTheWindowIsRecordedWithItsOwnCode() = runBlocking {
    val recorder = Recording()
    val driver =
      object : LlmDriver {
        override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode) =
          prompt.userText

        override suspend fun tokenWindow(engineMode: EngineMode) =
          object : TokenWindow {
            override val contextTokens = 100

            override suspend fun countTokens(text: String) = text.length

            override suspend fun overheadTokens(prompt: CorrectionPrompt) = 200
          }
      }

    InferenceEngineImpl(driver, { "/m.gguf" }, recorder).generateCorrection("he go to school")

    assertEquals(CorrectionErrorCode.PROMPT_TOO_LONG, recorder.events.single().first.errorCode)
  }

  /** A user does not lose their grammar fix because a database write failed. */
  @Test
  fun aRecorderThatThrowsDoesNotBreakTheCorrection() = runBlocking {
    val exploding =
      object : CorrectionRecorder {
        override suspend fun record(result: CorrectionResult, source: CorrectionSource): Unit =
          throw IllegalStateException("disk full")
      }

    val result =
      InferenceEngineImpl(shoutingDriver, { "/m.gguf" }, exploding)
        .generateCorrection("he go to school")

    assertTrue(result.isSuccess)
    assertEquals("HE GO TO SCHOOL", result.correctedText)
  }

  // --- a result nobody is waiting for any more (#34) --------------------------------------------

  /**
   * The overlay's field changed while the model was running, so its answer was thrown away.
   *
   * The row is still written — the inference really did run, and how long it took and whether it
   * failed are worth counting — but it is marked as not delivered, because the user never saw it.
   * Counting it as a correction is what made "corrections made" inflate by however many times the
   * field churned during a 15-second inference.
   */
  @Test
  fun aResultNobodyIsWaitingForIsRecordedAsNotDelivered() = runBlocking {
    val recorder = Recording()
    val engine = InferenceEngineImpl(shoutingDriver, { "/m.gguf" }, recorder)

    val result = engine.generateCorrection("he go to school", isStillWanted = { false })

    assertFalse(recorder.events.single().first.wasDelivered)
    // The caller is told the same thing the row says, rather than checking for itself.
    assertFalse(result.wasDelivered)
  }

  /** The ordinary case, and the default for a caller with nothing to wait for. */
  @Test
  fun aResultTheCallerStillWantsIsRecordedAsDelivered() = runBlocking {
    val recorder = Recording()
    val engine = InferenceEngineImpl(shoutingDriver, { "/m.gguf" }, recorder)

    engine.generateCorrection("he go to school")

    assertTrue(recorder.events.single().first.wasDelivered)
  }

  /** Asked after the model has answered, not before: that is the only moment it means anything. */
  @Test
  fun theCheckIsAskedOnceWhenInferenceHasFinished() = runBlocking {
    val order = mutableListOf<String>()
    val driver =
      object : LlmDriver {
        override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode): String {
          order += "inference"
          return prompt.userText.uppercase()
        }
      }

    InferenceEngineImpl(driver, { "/m.gguf" }, Recording())
      .generateCorrection("he go to school", isStillWanted = { order.add("check") })

    assertEquals(listOf("inference", "check"), order)
  }

  /** A failure that nobody is waiting for is counted the same way: recorded, not delivered. */
  @Test
  fun aFailedInferenceNobodyIsWaitingForIsAlsoNotDelivered() = runBlocking {
    val recorder = Recording()
    val engine = InferenceEngineImpl(failingDriver(), { "/m.gguf" }, recorder)

    engine.generateCorrection("he go to school", isStillWanted = { false })

    val (recorded, _) = recorder.events.single()
    assertFalse(recorded.isSuccess)
    assertFalse(recorded.wasDelivered)
  }

  /** A check that throws is a bug in the caller, and must not cost the user a correction. */
  @Test
  fun aCheckThatThrowsLeavesTheCorrectionAloneAndCountsIt() = runBlocking {
    val recorder = Recording()
    val engine = InferenceEngineImpl(shoutingDriver, { "/m.gguf" }, recorder)

    val result =
      engine.generateCorrection(
        "he go to school",
        isStillWanted = { throw IllegalStateException() },
      )

    assertEquals("HE GO TO SCHOOL", result.correctedText)
    assertTrue(recorder.events.single().first.wasDelivered)
  }

  /** The flag reaches the column, where the two questions are told apart by a query. */
  @Test
  fun theStoredRowSaysWhetherTheUserReceivedTheCorrection() = runBlocking {
    val database = openDatabase()
    val engine =
      InferenceEngineImpl(shoutingDriver, { "/m.gguf" }, RoomCorrectionRecorder(database))

    engine.generateCorrection("he go to school")
    engine.generateCorrection("she go to school", isStillWanted = { false })

    val dao = database.correctionEventDao()
    assertEquals(listOf(true, false), dao.all().map { it.delivered })
    assertEquals("two inferences ran", 2, dao.count())
    assertEquals("one correction was received", 1, dao.deliveredCount())
  }

  // --- what actually lands in the database ------------------------------------------------------

  /** The counts the Insights tab reads, end to end through the real recorder. */
  @Test
  fun theStoredRowCountsWhatTheCorrectionDid() = runBlocking {
    val database = openDatabase()
    val engine =
      InferenceEngineImpl(
        fixedDriver("He goes to school. She doesn't care."),
        { "/m.gguf" },
        RoomCorrectionRecorder(database) { 1_700_000_000_000L },
      )

    engine.generateCorrection(
      "he go to school. she dont care.",
      enforceNoThink = true,
      engineMode = EngineMode.QWEN3_GGUF,
      tone = DefaultTonePresets.POLISH,
      source = CorrectionSource.OVERLAY,
    )

    val row = database.correctionEventDao().all().single()
    assertEquals(1_700_000_000_000L, row.at)
    assertEquals("OVERLAY", row.source)
    assertEquals(DefaultTonePresets.POLISH_ID, row.tonePresetId)
    assertEquals("QWEN3_GGUF", row.engine)
    assertEquals(7, row.wordsIn)
    assertEquals(7, row.wordsOut)
    assertEquals(31, row.charsIn)
    assertEquals(36, row.charsOut)
    assertEquals(2, row.sentencesIn)
    assertEquals(2, row.sentencesOut)
    // "go" became "goes" and "dont" became "doesn't"; everything else only changed case.
    assertEquals(2, row.fixesTotal)
    assertEquals(2, row.fixesReplaced)
    assertEquals(0, row.fixesInserted)
    assertEquals(0, row.fixesDeleted)
    assertEquals(1, row.chunks)
    assertFalse(row.thinking)
    assertTrue(row.succeeded)
    assertNull(row.errorCode)
  }

  /** Text past one context window is several requests, and the row says how many. */
  @Test
  fun theStoredRowCountsTheRequestsALongTextTook() = runBlocking {
    val database = openDatabase()
    val driver =
      object : LlmDriver {
        override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode) =
          prompt.userText.uppercase()

        override suspend fun tokenWindow(engineMode: EngineMode) =
          object : TokenWindow {
            override val contextTokens = 400

            override suspend fun countTokens(text: String) = text.length

            override suspend fun overheadTokens(prompt: CorrectionPrompt) = 100
          }
      }
    val paragraph = List(16) { "aaaa" }.joinToString(" ")

    InferenceEngineImpl(driver, { "/m.gguf" }, RoomCorrectionRecorder(database))
      .generateCorrection(List(3) { paragraph }.joinToString("\n\n"))

    assertEquals(3, database.correctionEventDao().all().single().chunks)
  }

  /**
   * Nothing the user wrote reaches the file.
   *
   * The column whitelist is pinned in `CorrectionEventDaoTest`; this is the same promise checked
   * from the other end, on the bytes on disk, after a correction has gone through the whole path.
   */
  @Test
  fun noneOfTheUsersWritingReachesTheDatabaseFile() = runBlocking {
    val database = openDatabase()
    val engine =
      InferenceEngineImpl(shoutingDriver, { "/m.gguf" }, RoomCorrectionRecorder(database))

    engine.generateCorrection("my bank pin is $SECRET and i forgot it")

    val row = database.correctionEventDao().all().single()
    assertFalse(row.values().any { it.contains(SECRET, ignoreCase = true) })

    this@CorrectionRecorderTest.database?.close()
    this@CorrectionRecorderTest.database = null

    // ISO-8859-1 maps every byte to one character, so this searches the file as it is on disk.
    val onDisk =
      directory
        .listFiles()
        .orEmpty()
        .filter { it.name.startsWith("grammarfix.db") }
        .joinToString("\n") { it.readText(Charsets.ISO_8859_1) }
    assertTrue(onDisk.isNotEmpty())
    assertFalse(onDisk.contains(SECRET, ignoreCase = true))
  }

  // --- helpers ----------------------------------------------------------------------------------

  private fun openDatabase(): GrammarFixDatabase {
    database?.close()
    return Room.databaseBuilder<GrammarFixDatabase>(
        name = File(directory, "grammarfix.db").absolutePath
      )
      .setDriver(BundledSQLiteDriver())
      .setQueryCoroutineContext(Dispatchers.IO)
      .build()
      .also { database = it }
  }

  /** Answers with [answer] whatever it is asked, so the diff under test is fixed. */
  private fun fixedDriver(answer: String) =
    object : LlmDriver {
      override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode) = answer
    }

  private fun failingDriver() =
    object : LlmDriver {
      override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode): String =
        throw IllegalStateException("Model not downloaded yet")
    }

  private fun successfulResult() =
    CorrectionResult(
      originalText = "he go to school",
      correctedText = "He goes to school.",
      latencyMs = 10L,
      isNonThinkingMode = true,
      diffs = emptyList(),
    )

  /** Every value of the row as text, which is what a leak would have to hide in. */
  private fun CorrectionEventEntity.values(): List<String> =
    listOf(source, tonePresetId, engine, errorCode.orEmpty(), toString())

  private companion object {
    /** Distinctive enough that a stray copy anywhere in the file is unmistakable. */
    const val SECRET = "zqxjkv-correspondence"
  }
}
