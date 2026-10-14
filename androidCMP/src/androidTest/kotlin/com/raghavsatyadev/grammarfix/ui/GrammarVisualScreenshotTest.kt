package com.raghavsatyadev.grammarfix.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raghavsatyadev.grammarfix.engine.TextSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Grammarly-style suggestion overlay (issues #8, #30). */
@RunWith(AndroidJUnit4::class)
class GrammarVisualScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val text = "The cats is here"

  private val suggestions =
    listOf(
      TextSuggestion(original = "is", replacement = "are", startIndex = 9, endIndex = 11),
      TextSuggestion(original = "", replacement = "the", startIndex = 0, endIndex = 0),
    )

  private val events = mutableListOf<String>()

  private fun actions(onOpen: () -> Unit = { events += "open" }) =
    SuggestionOverlayActions(
      onAccept = { events += "accept:$it" },
      onDismiss = { events += "dismiss:$it" },
      onAcceptAll = { events += "acceptAll" },
      onOpenSheet = onOpen,
      onCloseSheet = { events += "close" },
      onDragStart = {},
      onDrag = {},
      onDragEnd = {},
      onDragCancel = {},
      onBubbleTap = { events += "openApp" },
      onOpenRewrite = {},
      onSelectTone = {},
      onSendInstruction = {},
      onInstructionFocus = {},
      onUseRewrite = {},
      onCloseRewrite = {},
    )

  private fun assertRenders() {
    val image: ImageBitmap = composeTestRule.onRoot().captureToImage()
    assertTrue(image.width > 0 && image.height > 0)
  }

  @Test
  fun idle_rendersBubble() {
    composeTestRule.setContent { SuggestionOverlay(SuggestionOverlayState.Idle, actions()) }
    composeTestRule.onNodeWithContentDescription("Checking as you type").assertExists()
    assertRenders()
  }

  @Test
  fun pill_showsHowManyFixesAreWaiting() {
    composeTestRule.setContent {
      SuggestionOverlay(SuggestionOverlayState.Review(text, suggestions), actions())
    }
    composeTestRule.onNodeWithText("Review suggestions").assertExists()
    composeTestRule.onNodeWithText("2").assertExists()
    assertRenders()
  }

  /** The collapsed pill must not apply anything: the user has not read the fix yet. */
  @Test
  fun pill_onlyOpensTheSheet() {
    composeTestRule.setContent {
      SuggestionOverlay(SuggestionOverlayState.Review(text, suggestions), actions())
    }

    composeTestRule.onNodeWithText("Review suggestions").performClick()

    assertEquals(listOf("open"), events)
  }

  @Test
  fun sheet_readsTheFixInItsSentence_withAcceptAndDismiss() {
    var state: SuggestionOverlayState by
      mutableStateOf(SuggestionOverlayState.Review(text, suggestions))
    composeTestRule.setContent {
      SuggestionOverlay(
        state,
        actions(onOpen = { state = SuggestionOverlayState.Review(text, suggestions, open = true) }),
      )
    }

    composeTestRule.onNodeWithText("Review suggestions").performClick()

    // The sentence is shown whole, with the fix inside it, rather than as a bare word pair.
    composeTestRule.onNodeWithText("The cats is are here").assertExists()
    composeTestRule.onNodeWithText("1 of 2 · Replace “is”").assertExists()

    composeTestRule.onNodeWithText("Accept").performClick()
    composeTestRule.onNodeWithText("Dismiss").performClick()

    assertEquals(listOf("accept:0", "dismiss:0"), events)
    assertRenders()
  }

  /** Accept-all survived the redesign; it moved into the sheet header. */
  @Test
  fun sheet_offersAcceptAllWhileMoreThanOneFixIsWaiting() {
    composeTestRule.setContent {
      SuggestionOverlay(
        SuggestionOverlayState.Review(text, suggestions, open = true),
        actions(),
      )
    }

    composeTestRule.onNodeWithContentDescription("Accept all (2)").performClick()

    assertEquals(listOf("acceptAll"), events)
  }

  @Test
  fun working_saysTheTextIsBeingCheckedAgain() {
    composeTestRule.setContent {
      SuggestionOverlay(SuggestionOverlayState.Working, actions())
    }
    composeTestRule.onNodeWithText("Checking your text…").assertExists()
    assertRenders()
  }

  @Test
  fun allClear_saysSoInsteadOfVanishing() {
    composeTestRule.setContent {
      SuggestionOverlay(SuggestionOverlayState.AllClear, actions())
    }
    composeTestRule.onNodeWithText("All clear").assertExists()
    composeTestRule.onNodeWithText("Nothing left to fix.").assertExists()
    assertRenders()
  }

  @Test
  fun error_explainsWhyNothingIsSuggested() {
    composeTestRule.setContent {
      SuggestionOverlay(SuggestionOverlayState.Error("Download the model first"), actions())
    }
    composeTestRule.onNodeWithText("Model unavailable").assertExists()
    composeTestRule.onNodeWithText("Download the model first").assertExists()
    assertRenders()
  }
}
