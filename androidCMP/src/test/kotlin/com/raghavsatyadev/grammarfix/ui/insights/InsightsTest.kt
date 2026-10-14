package com.raghavsatyadev.grammarfix.ui.insights

import com.raghavsatyadev.grammarfix.data.AcceptedDayBucket
import com.raghavsatyadev.grammarfix.data.AcceptedTotals
import com.raghavsatyadev.grammarfix.data.CorrectionTotals
import com.raghavsatyadev.grammarfix.data.DayBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The arithmetic behind the Insights tab, away from the screen that draws it. */
class InsightsTest {

  private fun totals(
    corrections: Int = 4,
    wordsIn: Int = 100,
    fixes: Int = 10,
    replaced: Int = 6,
    inserted: Int = 3,
    deleted: Int = 1,
  ) = CorrectionTotals(corrections, wordsIn, fixes, replaced, inserted, deleted)

  // --- the activity chart ------------------------------------------------------------------

  /**
   * Quiet days are drawn as quiet days.
   *
   * The query returns only the days that had something in them, so closing the gaps would show a
   * busy fortnight where there was one busy afternoon.
   */
  @Test
  fun daysWithNothingInThemBecomeZeroes() {
    val insights =
      insightsOf(
        current = totals(),
        previous = totals(),
        buckets = listOf(DayBucket(daysAgo = 0, wordsIn = 30, fixes = 3)),
        acceptedBuckets = listOf(AcceptedDayBucket(daysAgo = 0, fixes = 3)),
        days = 5,
      )

    assertEquals(listOf(0, 0, 0, 0, 30), insights.wordsPerDay)
    assertEquals(listOf(0, 0, 0, 0, 3), insights.fixesPerDay)
  }

  /** Oldest first, so today is the rightmost bar and the chart reads the way time does. */
  @Test
  fun todayIsTheLastColumn() {
    val insights =
      insightsOf(
        current = totals(),
        previous = totals(),
        buckets =
          listOf(
            DayBucket(daysAgo = 0, wordsIn = 10, fixes = 1),
            DayBucket(daysAgo = 2, wordsIn = 50, fixes = 5),
          ),
        days = 3,
      )

    assertEquals(listOf(50, 0, 10), insights.wordsPerDay)
  }

  /** A bucket older than the chart is dropped rather than wrapping onto the other end of it. */
  @Test
  fun aDayOlderThanTheChartIsIgnored() {
    val insights =
      insightsOf(
        current = totals(),
        previous = totals(),
        buckets =
          listOf(
            DayBucket(daysAgo = 9, wordsIn = 999, fixes = 99),
            DayBucket(daysAgo = 1, wordsIn = 20, fixes = 2),
          ),
        days = 3,
      )

    assertEquals(listOf(0, 20, 0), insights.wordsPerDay)
  }

  // --- the tiles ---------------------------------------------------------------------------

  @Test
  fun aTileComparesTheWindowWithTheOneBeforeIt() {
    val insights =
      insightsOf(
        current = totals(wordsIn = 150),
        previous = totals(wordsIn = 100),
        buckets = listOf(),
      )

    assertEquals(150, insights.wordsPolished.value)
    assertEquals(50, insights.wordsPolished.changePercent)
  }

  /**
   * A first week has nothing to compare against, and "+100%" against zero is a number pretending to
   * be information.
   */
  @Test
  fun aFirstWeekHasNoComparison() {
    val insights =
      insightsOf(
        current = totals(wordsIn = 150),
        previous = totals(wordsIn = 0),
        buckets = listOf(),
      )

    assertNull(insights.wordsPolished.changePercent)
  }

  @Test
  fun aQuieterWeekReadsAsANegativeChange() {
    assertEquals(-50, Metric(value = 50, previous = 100).changePercent)
  }

  // --- how clean the writing came in ---------------------------------------------------------

  @Test
  fun cleanlinessIsTheShareOfWordsThatNeededNoFix() {
    val insights =
      insightsOf(
        current = totals(wordsIn = 200, fixes = 20),
        previous = totals(),
        buckets = listOf(),
      )

    assertEquals(90, insights.cleanPercent)
  }

  /** No words at all is not the same as perfect writing. */
  @Test
  fun noWordsMeansNoFigureRatherThanAHundredPercent() {
    val insights =
      insightsOf(current = totals(wordsIn = 0, fixes = 0), previous = totals(), buckets = listOf())

    assertNull(insights.cleanPercent)
  }

  /**
   * An insertion adds a word that `words_in` never counted, so a short sentence with several of
   * them really can report more fixes than words. That is a true measurement and "-20% clean" is
   * still not a thing to put on a dashboard.
   */
  @Test
  fun moreFixesThanWordsIsClampedRatherThanShownNegative() {
    val insights =
      insightsOf(current = totals(wordsIn = 5, fixes = 9), previous = totals(), buckets = listOf())

    assertEquals(0, insights.cleanPercent)
  }

  // --- presentation ---------------------------------------------------------------------------

  @Test
  fun anEmptyWindowIsTheEmptyState() {
    val insights =
      insightsOf(current = totals(corrections = 0), previous = totals(), buckets = listOf())

    assertEquals(true, insights.isEmpty)
  }

  @Test
  fun bigNumbersAreGroupedSoTheyDoNotReadAsAPhoneNumber() {
    assertEquals("0", 0.grouped())
    assertEquals("999", 999.grouped())
    assertEquals("1,284", 1284.grouped())
    assertEquals("12,000", 12000.grouped())
    assertEquals("1,234,567", 1234567.grouped())
  }

  @Test
  fun theTilesSparklineIsTheLastSevenDays() {
    val buckets = (0 until 30).map { DayBucket(daysAgo = it, wordsIn = it, fixes = it) }

    val insights = insightsOf(totals(), totals(), buckets)

    assertEquals(TILE_DAYS, insights.wordsSpark.size)
    // daysAgo 6 down to 0, oldest first.
    assertEquals(listOf(6, 5, 4, 3, 2, 1, 0), insights.wordsSpark)
  }

  // --- accepted fixes ----------------------------------------------------------------------

  /**
   * "Fixes applied" is what the user accepted, not what the model suggested: a sentence checked
   * three times suggests its fixes three times, and a dismissed fix was never applied.
   */
  @Test
  fun fixesAppliedCountsAcceptedFixesNotSuggestions() {
    val insights =
      insightsOf(
        current = totals(fixes = 10, replaced = 6, inserted = 3, deleted = 1),
        previous = totals(fixes = 8),
        buckets = listOf(DayBucket(daysAgo = 0, wordsIn = 30, fixes = 10)),
        accepted = AcceptedTotals(fixes = 4, replaced = 3, inserted = 0, deleted = 1),
        previousAccepted = AcceptedTotals(fixes = 2, replaced = 2, inserted = 0, deleted = 0),
        acceptedBuckets = listOf(AcceptedDayBucket(daysAgo = 0, fixes = 4)),
        days = 3,
      )

    assertEquals(Metric(4, 2), insights.fixesApplied)
    assertEquals(listOf(0, 0, 4), insights.fixesPerDay)
    assertEquals(3, insights.replaced)
    assertEquals(1, insights.deleted)
    // Cleanliness is about the writing that came in, so it still reads the suggestions.
    assertEquals(90, insights.cleanPercent)
  }
}
