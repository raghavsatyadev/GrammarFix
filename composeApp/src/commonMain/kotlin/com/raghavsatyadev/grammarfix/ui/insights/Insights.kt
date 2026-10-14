package com.raghavsatyadev.grammarfix.ui.insights

import androidx.compose.runtime.Immutable
import com.raghavsatyadev.grammarfix.data.CorrectionTotals
import com.raghavsatyadev.grammarfix.data.DayBucket

/** How many days the activity chart covers. */
const val ACTIVITY_DAYS = 30

/** The window the tiles report, and the length of the window they compare it against. */
const val TILE_DAYS = 7

/** One headline figure and the same figure over the window before it. */
@Immutable
data class Metric(val value: Int, val previous: Int) {

  /**
   * Percent change against [previous], or null when there is nothing to compare against.
   *
   * A first week has no previous week, and "+100%" against zero is a number pretending to be
   * information. The tile shows nothing at all in that case.
   */
  val changePercent: Int?
    get() = if (previous == 0) null else ((value - previous) * 100) / previous
}

/**
 * Everything the Insights tab draws, already reduced to numbers.
 *
 * Built by [insightsOf] from what the database returns, so the screen does no arithmetic and the
 * arithmetic can be tested without a screen.
 */
@Immutable
data class Insights(
  val wordsPolished: Metric,
  val fixesApplied: Metric,
  /**
   * Share of words that arrived already right, as a percentage — "how clean your writing came in".
   *
   * Null when no words came in at all, which is not the same as 100%.
   */
  val cleanPercent: Int?,
  val previousCleanPercent: Int?,
  /** Words per day, oldest first, [ACTIVITY_DAYS] long. Quiet days are zero, not missing. */
  val wordsPerDay: List<Int>,
  /** Fixes per day over the same days, for the second tile's sparkline. */
  val fixesPerDay: List<Int>,
  val replaced: Int,
  val inserted: Int,
  val deleted: Int,
  /** Corrections in the reported window; zero means the tab has nothing to show yet. */
  val corrections: Int,
) {
  val isEmpty: Boolean
    get() = corrections == 0

  val fixesByKind: Int
    get() = replaced + inserted + deleted

  /** The last [TILE_DAYS] of [wordsPerDay], which is the window the tiles report. */
  val wordsSpark: List<Int>
    get() = wordsPerDay.takeLast(TILE_DAYS)

  val fixesSpark: List<Int>
    get() = fixesPerDay.takeLast(TILE_DAYS)
}

/**
 * Turns the database's answers into the figures the screen shows.
 *
 * [buckets] arrives keyed by how many days ago each one is and with the quiet days missing
 * entirely; they become zeroes here, oldest first, because a chart that closes the gaps would show
 * a busy week where there was a quiet one.
 */
fun insightsOf(
  current: CorrectionTotals,
  previous: CorrectionTotals,
  buckets: List<DayBucket>,
  days: Int = ACTIVITY_DAYS,
): Insights {
  val words = MutableList(days) { 0 }
  val fixes = MutableList(days) { 0 }
  for (bucket in buckets) {
    // Oldest first: the day the query ran is the last column of the chart.
    val index = days - 1 - bucket.daysAgo
    if (index in 0 until days) {
      words[index] = bucket.wordsIn
      fixes[index] = bucket.fixes
    }
  }

  return Insights(
    wordsPolished = Metric(current.wordsIn, previous.wordsIn),
    fixesApplied = Metric(current.fixes, previous.fixes),
    cleanPercent = cleanPercentOf(current),
    previousCleanPercent = cleanPercentOf(previous),
    wordsPerDay = words,
    fixesPerDay = fixes,
    replaced = current.replaced,
    inserted = current.inserted,
    deleted = current.deleted,
    corrections = current.corrections,
  )
}

/**
 * The share of words that needed no fix.
 *
 * Clamped, because a fix is not always a word: an insertion adds one that was never counted in
 * `words_in`, so a short sentence with several insertions can report more fixes than words. That is
 * a real measurement, but "-20% clean" is not a thing to put on a dashboard.
 */
private fun cleanPercentOf(totals: CorrectionTotals): Int? {
  if (totals.wordsIn <= 0) return null
  val clean = (totals.wordsIn - totals.fixes) * 100 / totals.wordsIn
  return clean.coerceIn(0, 100)
}

/**
 * `1284` as `1,284`.
 *
 * Common code has no number formatting, and a five-figure word count with no separator reads as a
 * phone number. Grouping only, no locale: the app's numbers are counts, not currency.
 */
fun Int.grouped(): String {
  val digits = toString()
  if (digits.length <= 3) return digits
  return digits.reversed().chunked(3).joinToString(",").reversed()
}
