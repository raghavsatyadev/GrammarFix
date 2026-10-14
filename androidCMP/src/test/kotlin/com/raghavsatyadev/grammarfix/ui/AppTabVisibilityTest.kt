package com.raghavsatyadev.grammarfix.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Playground is a developer tool: it must appear only when the host turns developer tools on
 * (`BuildConfig.DEBUG` on Android), and the app must still open on a tab that exists when it does
 * not.
 */
class AppTabVisibilityTest {

  @Test
  fun withDeveloperTools_playgroundIsPresent() {
    val tabs = visibleAppTabs(hasAssistantSetup = true, showDeveloperTools = true)

    assertTrue(tabs.contains(AppTab.PLAYGROUND))
    assertEquals(AppTab.entries.toList(), tabs)
  }

  @Test
  fun withoutDeveloperTools_playgroundIsAbsent() {
    val tabs = visibleAppTabs(hasAssistantSetup = true, showDeveloperTools = false)

    assertFalse(tabs.contains(AppTab.PLAYGROUND))
    assertEquals(listOf(AppTab.MODEL, AppTab.INSIGHTS, AppTab.TONES, AppTab.SETUP), tabs)
  }

  @Test
  fun developerToolsFlag_doesNotDisturbTheSetupFilter() {
    for (showDeveloperTools in listOf(true, false)) {
      assertTrue(
        "Setup belongs to a host that has an AssistantSetup",
        visibleAppTabs(hasAssistantSetup = true, showDeveloperTools).contains(AppTab.SETUP),
      )
      assertFalse(
        "Setup must stay hidden without an AssistantSetup",
        visibleAppTabs(hasAssistantSetup = false, showDeveloperTools).contains(AppTab.SETUP),
      )
    }
  }

  @Test
  fun withoutEither_onlyTheFourEveryHostHasRemain() {
    assertEquals(
      listOf(AppTab.MODEL, AppTab.INSIGHTS, AppTab.TONES),
      visibleAppTabs(hasAssistantSetup = false, showDeveloperTools = false),
    )
  }

  /** What ships: four tabs, and Insights is one of them (#20). */
  @Test
  fun theReleaseBarIsModelInsightsTonesSetup() {
    assertEquals(
      listOf(AppTab.MODEL, AppTab.INSIGHTS, AppTab.TONES, AppTab.SETUP),
      visibleAppTabs(hasAssistantSetup = true, showDeveloperTools = false),
    )
  }

  @Test
  fun visibleTabs_keepDeclarationOrder() {
    for (hasAssistantSetup in listOf(true, false)) {
      val withTools = visibleAppTabs(hasAssistantSetup, showDeveloperTools = true)
      val withoutTools = visibleAppTabs(hasAssistantSetup, showDeveloperTools = false)

      assertEquals(withoutTools, withTools.filter { it != AppTab.PLAYGROUND })
    }
  }

  @Test
  fun defaultTab_isAlwaysVisible() {
    for (hasAssistantSetup in listOf(true, false)) {
      for (showDeveloperTools in listOf(true, false)) {
        val tabs = visibleAppTabs(hasAssistantSetup, showDeveloperTools)
        assertTrue(
          "default tab must exist for hasAssistantSetup=$hasAssistantSetup " +
            "showDeveloperTools=$showDeveloperTools",
          tabs.contains(defaultAppTab(showDeveloperTools)),
        )
      }
    }
  }

  @Test
  fun defaultTab_isPlaygroundOnlyForDeveloperBuilds() {
    assertEquals(AppTab.PLAYGROUND, defaultAppTab(showDeveloperTools = true))
    assertEquals(AppTab.MODEL, defaultAppTab(showDeveloperTools = false))
  }
}
