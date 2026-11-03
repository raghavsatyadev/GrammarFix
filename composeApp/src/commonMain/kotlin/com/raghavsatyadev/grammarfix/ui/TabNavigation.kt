package com.raghavsatyadev.grammarfix.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.raghavsatyadev.grammarfix.engine.GrammarAssistantViewModel
import com.raghavsatyadev.grammarfix.ui.insights.InsightsScreen
import com.raghavsatyadev.grammarfix.ui.model.ModelScreen
import com.raghavsatyadev.grammarfix.ui.playground.PlaygroundScreen
import com.raghavsatyadev.grammarfix.ui.setup.AssistantSetup
import com.raghavsatyadev.grammarfix.ui.setup.SetupScreen
import com.raghavsatyadev.grammarfix.ui.tone.ToneScreen
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/** What the tab screens need from [GrammarFixApp]; it provides this around its NavDisplay. */
@Immutable
class TabHost(
  val viewModel: GrammarAssistantViewModel,
  val contentPadding: PaddingValues,
  val tabs: List<AppTab>,
  val startTab: AppTab,
  val assistantSetup: AssistantSetup?,
  val isModelReady: Boolean,
  val select: (AppTab) -> Unit,
)

val LocalTabHost = staticCompositionLocalOf<TabHost> { error("No TabHost") }

@Composable
private fun TabContent(route: TabRoute) {
  val host = LocalTabHost.current
  // A restored key can name a tab this build does not have; show the start tab instead.
  when (if (route.tab in host.tabs) route.tab else host.startTab) {
    AppTab.MODEL -> ModelScreen(host.viewModel, host.contentPadding)
    AppTab.INSIGHTS -> InsightsScreen(host.contentPadding)
    AppTab.PLAYGROUND -> PlaygroundScreen(host.viewModel, host.contentPadding)
    AppTab.TONES -> ToneScreen(host.contentPadding)
    AppTab.SETUP ->
      host.assistantSetup?.let {
        SetupScreen(
          setup = it,
          isModelReady = host.isModelReady,
          onOpenModelTab = { host.select(AppTab.MODEL) },
          contentPadding = host.contentPadding,
        )
      }
  }
}

/** The tab screens as a Koin `navigation` entry; [GrammarFixApp] shows them. */
@OptIn(KoinExperimentalAPI::class)
val tabNavigationModule = module { navigation<TabRoute> { route -> TabContent(route) } }
