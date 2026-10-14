package com.raghavsatyadev.grammarfix.data

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
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
 * `correction_event`, against a real database file.
 *
 * Three things are pinned here: that a correction can be appended and read back, that the DAO's
 * conflict handling works at all (Room 3.1.0-alpha01's `@Upsert` does not, which is why this app
 * uses `@Insert(IGNORE)`), and that the table cannot hold a word the user wrote.
 */
class CorrectionEventDaoTest {

  private lateinit var directory: File
  private var database: GrammarFixDatabase? = null

  @Before
  fun setUp() {
    directory = File.createTempFile("grammarfix-analytics", "").apply { delete() }
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

  /** Closes the database so the file can be read with a plain connection. */
  private fun closeDatabase() {
    database?.close()
    database = null
  }

  private fun event(
    id: Long = 0L,
    at: Long = 1_700_000_000_000L,
    wordsIn: Int = 6,
    succeeded: Boolean = true,
    errorCode: String? = null,
  ) =
    CorrectionEventEntity(
      id = id,
      at = at,
      source = "OVERLAY",
      tonePresetId = "polish",
      engine = "QWEN3_GGUF",
      wordsIn = wordsIn,
      wordsOut = 6,
      charsIn = 31,
      charsOut = 33,
      sentencesIn = 1,
      sentencesOut = 1,
      fixesTotal = 2,
      fixesReplaced = 1,
      fixesInserted = 1,
      fixesDeleted = 0,
      latencyMs = 412L,
      tokensPerSecond = 18.5f,
      chunks = 1,
      thinking = false,
      succeeded = succeeded,
      errorCode = errorCode,
    )

  // --- appending ------------------------------------------------------------------------------

  @Test
  fun appendsOneRowPerCorrectionAndReadsItBack() = runBlocking {
    val dao = openDatabase().correctionEventDao()

    dao.insert(event(at = 100L))
    dao.insert(event(at = 200L, wordsIn = 9))

    val rows = dao.all()
    assertEquals(2, rows.size)
    assertEquals(listOf(100L, 200L), rows.map { it.at })
    assertEquals(listOf(6, 9), rows.map { it.wordsIn })
    assertEquals("QWEN3_GGUF", rows.first().engine)
    assertEquals(18.5f, rows.first().tokensPerSecond, 0.001f)
    assertNull(rows.first().errorCode)
  }

  /** Two identical corrections a millisecond apart are two events, not one deduplicated row. */
  @Test
  fun identicalCorrectionsEachGetTheirOwnRow() = runBlocking {
    val dao = openDatabase().correctionEventDao()

    val first = dao.insert(event())
    val second = dao.insert(event())

    assertEquals(2, dao.count())
    assertTrue(first > 0L)
    assertTrue(second > first)
  }

  /** A failure is data too: the row lands, marked failed, carrying a code and no message. */
  @Test
  fun aFailedCorrectionIsStoredAsACode() = runBlocking {
    val dao = openDatabase().correctionEventDao()

    dao.insert(event(succeeded = false, errorCode = "PROMPT_TOO_LONG"))

    val row = dao.all().single()
    assertFalse(row.succeeded)
    assertEquals("PROMPT_TOO_LONG", row.errorCode)
  }

  /**
   * The conflict path, which is the one `@Upsert` cannot survive in this Room version.
   *
   * `@Upsert` against `BundledSQLiteDriver` throws a null-message `SQLException` the moment it has
   * to update an existing row, so the DAO uses `@Insert(onConflict = IGNORE)` instead. A replay of
   * a row id that already exists must therefore be quietly dropped — never throw, never duplicate,
   * and never overwrite what is already recorded.
   */
  @Test
  fun reInsertingAnExistingRowIdIsIgnoredRatherThanUpserted() = runBlocking {
    val dao = openDatabase().correctionEventDao()

    assertEquals(7L, dao.insert(event(id = 7L, wordsIn = 6)))
    val ignored = dao.insert(event(id = 7L, wordsIn = 999))

    assertEquals(-1L, ignored)
    assertEquals(1, dao.count())
    assertEquals(6, dao.all().single().wordsIn)
  }

  @Test
  fun trimmingDropsOnlyRowsOlderThanTheCutoff() = runBlocking {
    val dao = openDatabase().correctionEventDao()
    dao.insert(event(at = 100L))
    dao.insert(event(at = 500L))

    assertEquals(1, dao.deleteOlderThan(500L))

    assertEquals(listOf(500L), dao.all().map { it.at })
  }

  // --- privacy --------------------------------------------------------------------------------

  /**
   * The table cannot hold the user's writing.
   *
   * GrammarFix reads everything its user types in every app. Storing their prose in a queryable
   * database would be a different and far worse product, so the column list is pinned here: adding
   * one that could carry text — the input, the output, the fixed words, or a driver's error message
   * — fails this test and has to be argued for rather than merged by accident.
   */
  @Test
  fun theTableStoresNoUserText() {
    val database = openDatabase()
    runBlocking { database.correctionEventDao().insert(event()) }
    closeDatabase()

    val columns = columnsOf("correction_event")

    assertEquals(
      listOf(
        "id" to "INTEGER",
        "at" to "INTEGER",
        "source" to "TEXT",
        "tone_preset_id" to "TEXT",
        "engine" to "TEXT",
        "words_in" to "INTEGER",
        "words_out" to "INTEGER",
        "chars_in" to "INTEGER",
        "chars_out" to "INTEGER",
        "sentences_in" to "INTEGER",
        "sentences_out" to "INTEGER",
        "fixes_total" to "INTEGER",
        "fixes_replaced" to "INTEGER",
        "fixes_inserted" to "INTEGER",
        "fixes_deleted" to "INTEGER",
        "latency_ms" to "INTEGER",
        "tokens_per_second" to "REAL",
        "chunks" to "INTEGER",
        "thinking" to "INTEGER",
        "succeeded" to "INTEGER",
        "error_code" to "TEXT",
        "delivered" to "INTEGER",
      ),
      columns,
    )

    // The four text columns are ours, not the user's: two enum names and a tone id we issued.
    assertEquals(
      listOf("source", "tone_preset_id", "engine", "error_code"),
      columns.filter { it.second == "TEXT" }.map { it.first },
    )
  }

  // --- migration ------------------------------------------------------------------------------

  /**
   * A user upgrading from the release that shipped version 1 keeps their tones and gains the table.
   *
   * The version 1 database is built from the schema committed with that release, so this is the
   * file an installed app actually has, not an approximation of it. Room validates the migrated
   * database against version 2 as it opens, so a migration that produced the wrong table would fail
   * here rather than on a user's phone.
   */
  @Test
  fun migratesAVersionOneDatabaseWithoutLosingTones() = runBlocking {
    createVersionOneDatabase()

    val migrated = openDatabase()

    assertEquals("a reworded prompt", migrated.tonePresetDao().overrides().single().systemPrompt)
    assertEquals("polish", migrated.appSettingDao().get("selected_tone_id"))
    assertEquals(0, migrated.correctionEventDao().count())

    migrated.correctionEventDao().insert(event())
    assertEquals(1, migrated.correctionEventDao().count())

    closeDatabase()
    assertEquals(5L, userVersion())
  }

  /**
   * A user upgrading from the release that shipped version 2 keeps every correction they have made.
   *
   * `delivered` is added with a default of 1, so the rows written before the column existed keep
   * counting as the corrections they were already charted as. Losing a user's history to a new
   * analytics column would be a poor trade for a distinction they never asked for.
   */
  @Test
  fun migratesAVersionTwoDatabaseKeepingEveryRecordedCorrection() = runBlocking {
    createVersionTwoDatabase()

    val migrated = openDatabase()

    val row = migrated.correctionEventDao().all().single()
    assertEquals(1_700_000_000_000L, row.at)
    assertEquals(6, row.wordsIn)
    assertTrue("rows that predate the column are corrections the user received", row.delivered)
    assertEquals(1, migrated.correctionEventDao().deliveredCount())

    closeDatabase()
    assertEquals(5L, userVersion())
  }

  /** The exact DDL Room exported for version 1, plus the rows a real user would have. */
  private fun createVersionOneDatabase() = withConnection { connection ->
    listOf(
        "CREATE TABLE IF NOT EXISTS `tone_preset` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
          "`system_prompt` TEXT NOT NULL, `uses_thinking` INTEGER NOT NULL, " +
          "`max_output_tokens` INTEGER NOT NULL, `output_growth` REAL NOT NULL, " +
          "`is_built_in` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `tone_preset_override` (`preset_id` TEXT NOT NULL, " +
          "`system_prompt` TEXT NOT NULL, PRIMARY KEY(`preset_id`), " +
          "FOREIGN KEY(`preset_id`) REFERENCES `tone_preset`(`id`) ON UPDATE NO ACTION " +
          "ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `app_setting` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, " +
          "PRIMARY KEY(`key`))",
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
          "VALUES(42, '$VERSION_ONE_IDENTITY_HASH')",
        "PRAGMA user_version = 1",
        "INSERT INTO `tone_preset` VALUES('polish','Polish','Proofread it.',0,256,1.5,1)",
        "INSERT INTO `tone_preset_override` VALUES('polish','a reworded prompt')",
        "INSERT INTO `app_setting` VALUES('selected_tone_id','polish')",
      )
      .forEach(connection::execSQL)
  }

  /** The exact DDL Room exported for version 2, with one correction already recorded. */
  private fun createVersionTwoDatabase() = withConnection { connection ->
    listOf(
        "CREATE TABLE IF NOT EXISTS `tone_preset` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
          "`system_prompt` TEXT NOT NULL, `uses_thinking` INTEGER NOT NULL, " +
          "`max_output_tokens` INTEGER NOT NULL, `output_growth` REAL NOT NULL, " +
          "`is_built_in` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `tone_preset_override` (`preset_id` TEXT NOT NULL, " +
          "`system_prompt` TEXT NOT NULL, PRIMARY KEY(`preset_id`), " +
          "FOREIGN KEY(`preset_id`) REFERENCES `tone_preset`(`id`) ON UPDATE NO ACTION " +
          "ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `app_setting` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, " +
          "PRIMARY KEY(`key`))",
        "CREATE TABLE IF NOT EXISTS `correction_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT " +
          "NOT NULL, `at` INTEGER NOT NULL, `source` TEXT NOT NULL, `tone_preset_id` TEXT NOT " +
          "NULL, `engine` TEXT NOT NULL, `words_in` INTEGER NOT NULL, `words_out` INTEGER NOT " +
          "NULL, `chars_in` INTEGER NOT NULL, `chars_out` INTEGER NOT NULL, `sentences_in` " +
          "INTEGER NOT NULL, `sentences_out` INTEGER NOT NULL, `fixes_total` INTEGER NOT NULL, " +
          "`fixes_replaced` INTEGER NOT NULL, `fixes_inserted` INTEGER NOT NULL, " +
          "`fixes_deleted` INTEGER NOT NULL, `latency_ms` INTEGER NOT NULL, " +
          "`tokens_per_second` REAL NOT NULL, `chunks` INTEGER NOT NULL, `thinking` INTEGER " +
          "NOT NULL, `succeeded` INTEGER NOT NULL, `error_code` TEXT)",
        "CREATE INDEX IF NOT EXISTS `index_correction_event_at` ON `correction_event` (`at`)",
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
          "VALUES(42, '$VERSION_TWO_IDENTITY_HASH')",
        "PRAGMA user_version = 2",
        "INSERT INTO `tone_preset` VALUES('polish','Polish','Proofread it.',0,256,1.5,1)",
        "INSERT INTO `correction_event` VALUES(1,1700000000000,'OVERLAY','polish','QWEN3_GGUF'," +
          "6,6,31,33,1,1,2,1,1,0,412,18.5,1,0,1,NULL)",
      )
      .forEach(connection::execSQL)
  }

  private fun userVersion(): Long = withConnection { connection ->
    val statement = connection.prepare("PRAGMA user_version")
    try {
      statement.step()
      statement.getLong(0)
    } finally {
      statement.close()
    }
  }

  /** `(name, declared type)` for every column of [table], straight from SQLite. */
  private fun columnsOf(table: String): List<Pair<String, String>> = withConnection { connection ->
    val statement = connection.prepare("PRAGMA table_info('$table')")
    try {
      val columns = mutableListOf<Pair<String, String>>()
      while (statement.step()) columns += statement.getText(1) to statement.getText(2)
      columns
    } finally {
      statement.close()
    }
  }

  /** A plain SQLite connection to the same file, so Room's own validation is not what is asked. */
  private fun <T> withConnection(block: (SQLiteConnection) -> T): T {
    val connection = BundledSQLiteDriver().open(databaseFile.absolutePath)
    try {
      return block(connection)
    } finally {
      connection.close()
    }
  }

  private companion object {
    /** From `composeApp/schemas/com.raghavsatyadev.grammarfix.data.GrammarFixDatabase/1.json`. */
    const val VERSION_ONE_IDENTITY_HASH = "32254e24c83c8e36f2977d8e7afa7795"

    /** From `composeApp/schemas/com.raghavsatyadev.grammarfix.data.GrammarFixDatabase/2.json`. */
    const val VERSION_TWO_IDENTITY_HASH = "54c8949510203e875f8a200037e9b8a1"
  }
}
