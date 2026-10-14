package com.raghavsatyadev.grammarfix.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.raghavsatyadev.grammarfix.engine.AcceptedFixRecorder
import com.raghavsatyadev.grammarfix.engine.TextSuggestion
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * `accepted_fix`, the source of "Fixes applied", against a real database file.
 *
 * The privacy rule (#19) is pinned here like the other analytics tables: the columns are listed
 * exactly, and the words of an accepted fix are shown to never reach the table.
 */
class AcceptedFixDaoTest {

  private lateinit var directory: File
  private var database: GrammarFixDatabase? = null

  @Before
  fun setUp() {
    directory = File.createTempFile("grammarfix-accepted", "").apply { delete() }
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

  private val replace = TextSuggestion(original = "buyed", replacement = "bought")
  private val remove = TextSuggestion(original = "has", replacement = "")
  private val add = TextSuggestion(original = "", replacement = "the")

  @Test
  fun eachAcceptedFixIsOneRowOfItsKindAndNoText() = runBlocking {
    val dao = openDatabase().acceptedFixDao()
    AcceptedFixRecorder(dao, now = { 1_700_000_000_000L }).record(listOf(replace, remove, add))

    val rows = dao.all()
    assertEquals(listOf("REPLACED", "DELETED", "INSERTED"), rows.map { it.kind })
    assertEquals(setOf(1_700_000_000_000L), rows.map { it.at }.toSet())

    database?.close()
    database = null
    assertEquals(
      listOf("id" to "INTEGER", "at" to "INTEGER", "kind" to "TEXT"),
      columnsOf("accepted_fix"),
    )
  }

  @Test
  fun totalsCountOnlyTheWindowAndSplitByKind() = runBlocking {
    val dao = openDatabase().acceptedFixDao()
    AcceptedFixRecorder(dao, now = { 1_000L }).record(listOf(replace))
    AcceptedFixRecorder(dao, now = { 5_000L }).record(listOf(replace, replace, remove))

    assertEquals(AcceptedTotals(3, 2, 0, 1), dao.totalsBetween(2_000L, 10_000L))
    assertEquals(AcceptedTotals(0, 0, 0, 0), dao.totalsBetween(20_000L, 30_000L))
  }

  /** Undo takes back exactly the fixes the undone action applied, newest first. */
  @Test
  fun undoRemovesTheNewestFixesOnly() = runBlocking {
    val dao = openDatabase().acceptedFixDao()
    val recorder = AcceptedFixRecorder(dao, now = { 1_000L })
    recorder.record(listOf(replace))
    recorder.record(listOf(remove, add))

    recorder.unrecord(2)

    assertEquals(listOf("REPLACED"), dao.all().map { it.kind })
  }

  @Test
  fun dailyBucketsCountFixesPerDay() = runBlocking {
    val dao = openDatabase().acceptedFixDao()
    val now = 1_700_000_000_000L
    AcceptedFixRecorder(dao, now = { now - 1_000L }).record(listOf(replace, remove))

    assertEquals(listOf(AcceptedDayBucket(daysAgo = 0, fixes = 2)), dao.dailyBuckets(0L, now))
  }

  private fun columnsOf(table: String): List<Pair<String, String>> {
    val connection = BundledSQLiteDriver().open(databaseFile.absolutePath)
    try {
      val statement = connection.prepare("PRAGMA table_info('$table')")
      try {
        val columns = mutableListOf<Pair<String, String>>()
        while (statement.step()) columns += statement.getText(1) to statement.getText(2)
        return columns
      } finally {
        statement.close()
      }
    } finally {
      connection.close()
    }
  }
}
