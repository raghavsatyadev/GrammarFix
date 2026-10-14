package com.raghavsatyadev.grammarfix.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.raghavsatyadev.grammarfix.engine.EngineMode
import com.raghavsatyadev.grammarfix.engine.RoomToneRecorder
import com.raghavsatyadev.grammarfix.engine.ToneReading
import java.io.File
import kotlinx.coroutines.CoroutineScope
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
 * `tone_event` and the tone-detection setting, against a real database file (#32).
 *
 * The privacy rule (#19) is pinned here the way `correction_event`'s is: the table's columns are
 * listed exactly, and a reading's summary — the model's words about the user's text — is shown to
 * never reach it.
 */
class ToneEventDaoTest {

  private lateinit var directory: File
  private var database: GrammarFixDatabase? = null

  @Before
  fun setUp() {
    directory = File.createTempFile("grammarfix-tone", "").apply { delete() }
    directory.mkdirs()
  }

  @After
  fun tearDown() {
    database?.close()
    directory.deleteRecursively()
  }

  private val databaseFile
    get() = File(directory, "grammarfix.db")

  private fun openDatabase(): GrammarFixDatabase {
    database?.close()
    return Room.databaseBuilder<GrammarFixDatabase>(name = databaseFile.absolutePath)
      .setDriver(BundledSQLiteDriver())
      .setQueryCoroutineContext(Dispatchers.IO)
      .build()
      .also { database = it }
  }

  @Test
  fun theRecorderKeepsLabelsAndNeverTheSummary() = runBlocking {
    val db = openDatabase()
    val recorder = RoomToneRecorder(db.toneEventDao(), now = { 1_700_000_000_000L })

    recorder.record(
      ToneReading(listOf("direct", "frustrated"), "Says the design will not change again."),
      latencyMs = 1_234L,
      engineMode = EngineMode.QWEN3_GGUF,
    )

    val row = db.toneEventDao().all().single()
    assertEquals("direct,frustrated", row.labels)
    assertEquals("QWEN3_GGUF", row.engine)
    assertEquals(1_234L, row.latencyMs)
    assertEquals(listOf("direct", "frustrated"), recorder.latestLabels())
    assertFalse(row.toString().contains("design"))
  }

  @Test
  fun aReadingWithNoVocabularyLabelWritesNothing() = runBlocking {
    val db = openDatabase()
    val recorder = RoomToneRecorder(db.toneEventDao())

    recorder.record(ToneReading(listOf("my secret words"), ""), 1L, EngineMode.QWEN3_GGUF)

    assertTrue(db.toneEventDao().all().isEmpty())
    assertNull(recorder.latestLabels())
  }

  @Test
  fun theTableHasNoColumnForText() = runBlocking {
    openDatabase()
      .toneEventDao()
      .insert(ToneEventEntity(at = 1L, labels = "casual", engine = "X", latencyMs = 1L))
    database?.close()
    database = null

    val connection = BundledSQLiteDriver().open(databaseFile.absolutePath)
    val columns = mutableListOf<Pair<String, String>>()
    try {
      val statement = connection.prepare("PRAGMA table_info('tone_event')")
      try {
        while (statement.step()) columns += statement.getText(1) to statement.getText(2)
      } finally {
        statement.close()
      }
    } finally {
      connection.close()
    }

    assertEquals(
      listOf(
        "id" to "INTEGER",
        "at" to "INTEGER",
        "labels" to "TEXT",
        "engine" to "TEXT",
        "latency_ms" to "INTEGER",
      ),
      columns,
    )
  }

  @Test
  fun detectToneWhileTypingIsOffByDefaultAndSurvivesARestart() = runBlocking {
    val first =
      AppSettingsRepository(openDatabase().appSettingDao(), CoroutineScope(Dispatchers.IO))
    first.awaitLoaded()
    assertFalse(first.detectToneWhileTyping.value)

    first.setDetectToneWhileTyping(true)
    assertTrue(first.detectToneWhileTyping.value)

    val second =
      AppSettingsRepository(openDatabase().appSettingDao(), CoroutineScope(Dispatchers.IO))
    second.awaitLoaded()
    assertTrue(second.detectToneWhileTyping.value)
  }
}
