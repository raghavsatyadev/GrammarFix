package com.raghavsatyadev.grammarfix.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raghavsatyadev.grammarfix.onboardedSettings
import com.raghavsatyadev.grammarfix.ui.setup.AssistantSetup
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The app shell: tabs, the empty editor, and the Android-only Setup tab. */
@RunWith(AndroidJUnit4::class)
class GrammarLiveUiTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val setup =
    AssistantSetup(
      hasOverlayPermission = true,
      isAccessibilityEnabled = false,
      isAssistantRunning = false,
      onOpenOverlaySettings = {},
      onOpenAccessibilitySettings = {},
      onStartAssistant = {},
      onStopAssistant = {},
    )

  @Test
  fun playground_opensFirst_withEmptyEditor() {
    composeTestRule.setContent {
      GrammarFixApp(
        assistantSetup = setup,
        showDeveloperTools = true,
        settings = onboardedSettings(),
      )
    }

    composeTestRule.onNodeWithText("GrammarFix").assertIsDisplayed()
    composeTestRule.onNodeWithText("Type or paste a sentence to check…").assertIsDisplayed()
  }

  @Test
  fun typedText_showsInTheEditor() {
    composeTestRule.setContent {
      GrammarFixApp(
        assistantSetup = setup,
        showDeveloperTools = true,
        settings = onboardedSettings(),
      )
    }

    val sentence = "Is user ofay? Do he needs any halp."
    composeTestRule.onNodeWithText("Type or paste a sentence to check…").performTextInput(sentence)

    composeTestRule.onNodeWithText(sentence).assertIsDisplayed()
  }

  @Test
  fun setupTab_showsPermissionState() {
    composeTestRule.setContent {
      GrammarFixApp(assistantSetup = setup, settings = onboardedSettings())
    }

    composeTestRule.onNodeWithContentDescription("Setup").performClick()

    composeTestRule.onNodeWithText("Display over other apps").assertIsDisplayed()
    // The accessibility service is off in [setup]; the model card may say "Needed" too.
    composeTestRule.onAllNodesWithText("Needed")[0].assertIsDisplayed()
  }

  @Test
  fun withoutAssistant_setupTabIsHidden() {
    composeTestRule.setContent { GrammarFixApp(settings = onboardedSettings()) }

    composeTestRule.onNodeWithContentDescription("Setup").assertDoesNotExist()
  }

  @Test
  fun withoutDeveloperTools_playgroundTabIsHidden() {
    composeTestRule.setContent {
      GrammarFixApp(
        assistantSetup = setup,
        showDeveloperTools = false,
        settings = onboardedSettings(),
      )
    }

    composeTestRule.onNodeWithContentDescription("Playground").assertDoesNotExist()
    composeTestRule.onNodeWithContentDescription("Model").assertIsDisplayed()
  }
}
