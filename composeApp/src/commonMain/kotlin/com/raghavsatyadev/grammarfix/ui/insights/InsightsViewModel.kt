package com.raghavsatyadev.grammarfix.ui.insights

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raghavsatyadev.grammarfix.data.CorrectionEventDao
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class InsightsUiState(
  /** True until the first read finishes. The tab must not flash its empty state on the way in. */
  val isLoading: Boolean = true,
  val insights: Insights? = null,
)

/**
 * Reads the Insights tab's figures out of `correction_event`.
 *
 * The windows are rolling — the last seven days against the seven before them — rather than
 * calendar weeks. A calendar week means asking what day it is in the user's own timezone from three
 * source sets; a rolling window is the same arithmetic everywhere, and on a Wednesday it is also
 * the more useful comparison. The screen says "last 7 days" so the number is not mistaken for one.
 *
 * Day bucketing for the chart is left to SQLite, which knows the device's local date, with [now]
 * passed in so a test can pin the day the chart ends on.
 */
class InsightsViewModel(
  private val dao: CorrectionEventDao,
  private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ViewModel() {

  private val _state = MutableStateFlow(InsightsUiState())
  val state: StateFlow<InsightsUiState> = _state.asStateFlow()

  init {
    refresh()
  }

  /** Re-reads the figures. The tab is cheap to open and the numbers change while it is closed. */
  fun refresh() {
    viewModelScope.launch { _state.value = InsightsUiState(isLoading = false, insights = load()) }
  }

  private suspend fun load(): Insights {
    val nowMs = now()
    val window = TILE_DAYS * DAY_MS
    return insightsOf(
      current = dao.totalsBetween(nowMs - window, nowMs),
      previous = dao.totalsBetween(nowMs - 2 * window, nowMs - window),
      // A day more than the chart draws, so the oldest column is whole however the local day falls.
      buckets = dao.dailyBuckets(nowMs - (ACTIVITY_DAYS + 1) * DAY_MS, nowMs),
    )
  }

  private companion object {
    const val DAY_MS = 24L * 60 * 60 * 1000
  }
}
