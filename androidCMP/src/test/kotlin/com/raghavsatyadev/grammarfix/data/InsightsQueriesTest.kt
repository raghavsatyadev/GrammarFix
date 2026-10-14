package com.raghavsatyadev.grammarfix.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The two queries the Insights tab is built on, against a real database file.
 *
 * What is pinned here is what the tab is allowed to count. Everything else about it — layout,
 * colours, the shape of the chart — can change without touching these numbers.
 */
class InsightsQueriesTest {

  private lateinit var directory: File
  private var database: GrammarFixDatabase? = null

  /**
   * Noon UTC, so that "a day earlier" is a different local date in every timezone this app will
   * plausibly run in. The query converts both ends to the device's local date, and anchoring at
   * midnight would make the expected bucket depend on where the test happens to run.
   */
  private val noonUtc = (1_772_000_000_000L / DAY) * DAY + DAY / 2

  @Before
  fun setUp() {
    directory = File.createTempFile("grammarfix-insights", "").apply { delete() }
    directory.mkdirs()
  }

  @After
  fun tearDown() {
    database?.close()
    directory.deleteRecursively()
  }

  private fun openDao(): CorrectionEventDao {
    database?.close()
    return Room.databaseBuilder<GrammarFixDatabase>(
        name = File(directory, "grammarfix.db").absolutePath
      )
      .setDriver(BundledSQLiteDriver())
      .setQueryCoroutineContext(Dispatchers.IO)
      .build()
      .also { database = it }
      .correctionEventDao()
  }

  private fun event(
    at: Long,
    wordsIn: Int = 10,
    fixes: Int = 2,
    delivered: Boolean = true,
    succeeded: Boolean = true,
  ) =
    CorrectionEventEntity(
      at = at,
      source = "OVERLAY",
      tonePresetId = "polish",
      engine = "QWEN3_GGUF",
      wordsIn = wordsIn,
      wordsOut = wordsIn,
      charsIn = wordsIn * 5,
      charsOut = wordsIn * 5,
      sentencesIn = 1,
      sentencesOut = 1,
      fixesTotal = fixes,
      fixesReplaced = fixes,
      fixesInserted = 0,
      fixesDeleted = 0,
      latencyMs = 400L,
      tokensPerSecond = 18f,
      chunks = 1,
      thinking = false,
      succeeded = succeeded,
      errorCode = if (succeeded) null else "INFERENCE_FAILED",
      delivered = delivered,
    )

  // --- totals ---------------------------------------------------------------------------------

  /** A fresh install has no rows at all, and `SUM` over no rows is null rather than zero. */
  @Test
  fun anEmptyDatabaseReportsZeroesRatherThanFailing() = runBlocking {
    val totals = openDao().totalsBetween(0, noonUtc)

    assertEquals(0, totals.corrections)
    assertEquals(0, totals.wordsIn)
    assertEquals(0, totals.fixes)
  }

  @Test
  fun totalsAddUpEveryCorrectionInTheWindow() = runBlocking {
    val dao = openDao()
    dao.insert(event(at = noonUtc - DAY, wordsIn = 10, fixes = 2))
    dao.insert(event(at = noonUtc - 2 * DAY, wordsIn = 30, fixes = 5))

    val totals = dao.totalsBetween(noonUtc - 3 * DAY, noonUtc)

    assertEquals(2, totals.corrections)
    assertEquals(40, totals.wordsIn)
    assertEquals(7, totals.fixes)
  }

  /** The window is half-open, so last week's numbers are not also this week's. */
  @Test
  fun aRowOutsideTheWindowIsNotCounted() = runBlocking {
    val dao = openDao()
    dao.insert(event(at = noonUtc - 8 * DAY, wordsIn = 500))
    dao.insert(event(at = noonUtc - DAY, wordsIn = 10))

    assertEquals(10, dao.totalsBetween(noonUtc - 7 * DAY, noonUtc).wordsIn)
    assertEquals(500, dao.totalsBetween(noonUtc - 14 * DAY, noonUtc - 7 * DAY).wordsIn)
  }

  /**
   * A result the overlay threw away was never seen by the user (#34), so it is not a correction
   * they received and it must not move "how clean your writing came in".
   */
  @Test
  fun aResultTheUserNeverSawIsNotCounted() = runBlocking {
    val dao = openDao()
    dao.insert(event(at = noonUtc - DAY, wordsIn = 10))
    dao.insert(event(at = noonUtc - DAY, wordsIn = 999, delivered = false))

    assertEquals(10, dao.totalsBetween(noonUtc - 7 * DAY, noonUtc).wordsIn)
  }

  /** A failure carries no counts, so counting it would drag every average towards zero. */
  @Test
  fun aFailedInferenceIsNotCounted() = runBlocking {
    val dao = openDao()
    dao.insert(event(at = noonUtc - DAY, wordsIn = 10))
    dao.insert(event(at = noonUtc - DAY, wordsIn = 0, fixes = 0, succeeded = false))

    assertEquals(1, dao.totalsBetween(noonUtc - 7 * DAY, noonUtc).corrections)
  }

  // --- daily buckets --------------------------------------------------------------------------

  @Test
  fun everyDayIsBucketedByHowLongAgoItWas() = runBlocking {
    val dao = openDao()
    dao.insert(event(at = noonUtc, wordsIn = 5))
    dao.insert(event(at = noonUtc - DAY, wordsIn = 10))
    dao.insert(event(at = noonUtc - 3 * DAY, wordsIn = 20))

    // `now` is a moment after the last row, as it would be in the app.
    val buckets = dao.dailyBuckets(from = noonUtc - 30 * DAY, now = noonUtc + 1)

    assertEquals(listOf(0, 1, 3), buckets.map { it.daysAgo })
    assertEquals(listOf(5, 10, 20), buckets.map { it.wordsIn })
  }

  /** Several corrections on one day are one bar, not several. */
  @Test
  fun correctionsOnTheSameDayAreOneBucket() = runBlocking {
    val dao = openDao()
    dao.insert(event(at = noonUtc - DAY, wordsIn = 10, fixes = 1))
    dao.insert(event(at = noonUtc - DAY + 60_000, wordsIn = 15, fixes = 2))

    val bucket = dao.dailyBuckets(from = noonUtc - 30 * DAY, now = noonUtc).single()

    assertEquals(1, bucket.daysAgo)
    assertEquals(25, bucket.wordsIn)
    assertEquals(3, bucket.fixes)
  }

  /** The chart counts what the tiles count: nothing the user never saw. */
  @Test
  fun bucketsExcludeUndeliveredAndFailedRunsToo() = runBlocking {
    val dao = openDao()
    dao.insert(event(at = noonUtc - DAY, wordsIn = 10))
    dao.insert(event(at = noonUtc - DAY, wordsIn = 999, delivered = false))
    dao.insert(event(at = noonUtc - DAY, wordsIn = 777, succeeded = false))

    assertEquals(10, dao.dailyBuckets(from = noonUtc - 30 * DAY, now = noonUtc).single().wordsIn)
  }

  private companion object {
    const val DAY = 24L * 60 * 60 * 1000
  }
}
