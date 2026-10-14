package com.raghavsatyadev.grammarfix.ui.insights

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.raghavsatyadev.grammarfix.data.AcceptedDayBucket
import com.raghavsatyadev.grammarfix.data.AcceptedFixDao
import com.raghavsatyadev.grammarfix.data.AcceptedFixEntity
import com.raghavsatyadev.grammarfix.data.AcceptedTotals
import com.raghavsatyadev.grammarfix.data.CorrectionEventDao
import com.raghavsatyadev.grammarfix.data.CorrectionEventEntity
import com.raghavsatyadev.grammarfix.data.CorrectionTotals
import com.raghavsatyadev.grammarfix.data.DayBucket
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Insights ViewModel outlives the tab. Rows the overlay writes while the user is in another app
 * must show up the next time the tab is opened, not only after the app is restarted.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the screen gets its ViewModel handed in, so the app's Koin graph is not
// needed, and starting it would leak into the next test in the same JVM.
@Config(sdk = [34], application = android.app.Application::class)
class InsightsRefreshTest {

  @get:Rule val composeTestRule = createComposeRule()

  private class FakeDao : CorrectionEventDao {
    var corrections = 0

    override suspend fun insert(event: CorrectionEventEntity) = 0L

    override suspend fun all() = emptyList<CorrectionEventEntity>()

    override suspend fun count() = corrections

    override suspend fun deliveredCount() = corrections

    override suspend fun totalsBetween(from: Long, until: Long) =
      CorrectionTotals(corrections, corrections * 10, corrections, corrections, 0, 0)

    override suspend fun dailyBuckets(from: Long, now: Long) =
      if (corrections == 0) emptyList() else listOf(DayBucket(0, corrections * 10, corrections))

    override suspend fun deleteOlderThan(cutoff: Long) = 0
  }

  private class NoAcceptedFixes : AcceptedFixDao {
    override suspend fun insertAll(fixes: List<AcceptedFixEntity>) = Unit

    override suspend fun deleteLatest(count: Int) = 0

    override suspend fun all() = emptyList<AcceptedFixEntity>()

    override suspend fun totalsBetween(from: Long, until: Long) = AcceptedTotals.None

    override suspend fun dailyBuckets(from: Long, now: Long) = emptyList<AcceptedDayBucket>()
  }

  @Test
  fun reopeningTheTabShowsCorrectionsMadeWhileItWasClosed() {
    val dao = FakeDao()
    val viewModel = InsightsViewModel(dao, NoAcceptedFixes())
    var tabShown by mutableStateOf(true)
    composeTestRule.setContent {
      if (tabShown) InsightsScreen(contentPadding = PaddingValues(), viewModel = viewModel)
    }
    composeTestRule.onNodeWithText("Nothing to show yet").assertExists()

    tabShown = false
    composeTestRule.waitForIdle()
    dao.corrections = 3
    tabShown = true
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithText("Nothing to show yet").assertDoesNotExist()
  }
}
