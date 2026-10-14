@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.raghavsatyadev.grammarfix.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.raghavsatyadev.grammarfix.engine.DefaultTonePresets
import com.raghavsatyadev.grammarfix.engine.DiffItem
import com.raghavsatyadev.grammarfix.engine.TextSuggestion
import com.raghavsatyadev.grammarfix.engine.ToneCheck
import com.raghavsatyadev.grammarfix.engine.TonePreset
import com.raghavsatyadev.grammarfix.engine.isWithin
import com.raghavsatyadev.grammarfix.library.support.theme.AppTheme
import grammarfix.composeapp.generated.resources.Res
import grammarfix.composeapp.generated.resources.overlay_accept_one
import grammarfix.composeapp.generated.resources.overlay_bubble_tap
import grammarfix.composeapp.generated.resources.overlay_checking
import grammarfix.composeapp.generated.resources.overlay_close_assistant
import grammarfix.composeapp.generated.resources.overlay_dismiss
import grammarfix.composeapp.generated.resources.overlay_listening
import grammarfix.composeapp.generated.resources.overlay_model_unavailable
import grammarfix.composeapp.generated.resources.overlay_open_sheet
import grammarfix.composeapp.generated.resources.overlay_undo
import org.jetbrains.compose.resources.stringResource

/** What the floating overlay shows. */
@Immutable
sealed interface SuggestionOverlayState {
  /** Watching the focused field; nothing to suggest. */
  data object Idle : SuggestionOverlayState

  /** The user typed; the model is checking the new text. */
  data object Checking : SuggestionOverlayState

  /**
   * Fixes waiting to be reviewed.
   *
   * [text] is the text they were found in. The sheet reads each fix inside that sentence rather
   * than on its own, so the overlay needs the whole of it, not just the words that change.
   */
  data class Review(
    val text: String,
    val items: List<TextSuggestion>,
    /** Which fix the sheet is showing. */
    val index: Int = 0,
    /** The sheet is open, instead of the collapsed pill. */
    val open: Boolean = false,
    /**
     * The text the user selected when the sheet opened. The sheet then offers only the fixes inside
     * it; null covers the whole text.
     */
    val scope: IntRange? = null,
  ) : SuggestionOverlayState {
    val current: TextSuggestion?
      get() = items.getOrNull(index)

    /** The fixes the sheet lists, each with its index in [items]. */
    val visible: List<IndexedValue<TextSuggestion>>
      get() = items.withIndex().filter { it.value.isWithin(scope) }

    /** The text the sheet speaks for: the selection, or all of [text]. */
    val scopedText: String
      get() =
        scope?.let {
          text.substring(it.first.coerceIn(0, text.length), (it.last + 1).coerceIn(0, text.length))
        } ?: text
  }

  /** A fix was applied; the sheet holds its place while the text is checked again. */
  data object Working : SuggestionOverlayState

  /** The sheet is open and every fix has been handled; says so instead of vanishing. */
  data object AllClear : SuggestionOverlayState

  /**
   * The rewrite panel (#31): the whole of [original] rewritten in one tone, picked from a row of
   * chips, or by an instruction the user typed.
   *
   * It opens from the review sheet's header and shares its frame, so it replaces the sheet in place
   * rather than appearing as a second window.
   */
  data class Rewrite(
    /** The field's text when the panel opened; every chip rewrites this, never a rewrite of it. */
    val original: String,
    /** Built-in tones and the user's own, in the order the Tones tab lists them. */
    val tones: List<TonePreset>,
    /**
     * The tone behind [result]: a chip's id, or [DefaultTonePresets.INSTRUCTION_ID] for a typed
     * instruction, in which case no chip is selected.
     */
    val selectedId: String,
    /** The typed instruction behind [result]. Shown as the title; never stored anywhere. */
    val instruction: String? = null,
    val result: RewriteResult = RewriteResult.Loading,
  ) : SuggestionOverlayState

  data class Error(val message: String) : SuggestionOverlayState
}

/** Where a rewrite has got to. The panel keeps its size through all three. */
@Immutable
sealed interface RewriteResult {
  /** The model is running; the panel shows skeleton lines and cannot be accepted yet. */
  data object Loading : RewriteResult

  /** The model's rewrite, and its word diff against the original for highlighting the changes. */
  data class Ready(val text: String, val diffs: List<DiffItem>) : RewriteResult

  data class Failed(val message: String) : RewriteResult
}

/** True while the overlay is a docked sheet rather than a floating bubble. */
val SuggestionOverlayState.isDocked: Boolean
  get() =
    when (this) {
      SuggestionOverlayState.Working,
      SuggestionOverlayState.AllClear,
      is SuggestionOverlayState.Rewrite -> true
      is SuggestionOverlayState.Review -> open
      else -> false
    }

/** Everything the overlay can ask its host to do. Indexes point into the current suggestions. */
@Immutable
data class SuggestionOverlayActions(
  val onAccept: (index: Int) -> Unit,
  val onDismiss: (index: Int) -> Unit,
  val onAcceptAll: () -> Unit,
  /** The collapsed pill was tapped: dock the sheet and start reviewing. */
  val onOpenSheet: () -> Unit,
  /** Back to the pill, with the remaining fixes still waiting. */
  val onCloseSheet: () -> Unit,
  /** A drag has begun: the host shows its drop-to-close target ([DismissTarget]). */
  val onDragStart: () -> Unit,
  /** The user is dragging the overlay; move the window by this amount. */
  val onDrag: (Offset) -> Unit,
  /** The user let go. If the overlay came to rest on the close target, the host closes it. */
  val onDragEnd: () -> Unit,
  /** The drag was interrupted rather than released, so it must never close anything. */
  val onDragCancel: () -> Unit,
  /**
   * The idle bubble was tapped. As in Grammarly, a field with no issues opens the rewrite panel
   * over its text; with no field, or nothing in it to rewrite, the host brings the app to the front
   * instead (#23). Only the host can tell which, so the bubble just reports the tap.
   */
  val onBubbleTap: () -> Unit,
  /** The sheet's rewrite button: open the rewrite panel over the field's whole text (#31). */
  val onOpenRewrite: () -> Unit,
  /** A tone chip was tapped: drop the rewrite in flight and start this one. */
  val onSelectTone: (id: String) -> Unit,
  /** The instruction field was sent: rewrite with the user's own words as a one-off tone. */
  val onSendInstruction: (instruction: String) -> Unit,
  /**
   * The instruction field wants the keyboard, or is done with it. The overlay's window never takes
   * focus otherwise, so the host has to let it for as long as the user is typing an instruction.
   */
  val onInstructionFocus: (focused: Boolean) -> Unit,
  /** `Use this version`: write the rewrite into the field, the way Accept writes a fix. */
  val onUseRewrite: () -> Unit,
  /** Leave the rewrite panel for whatever it was opened from: the sheet, or the idle bubble. */
  val onCloseRewrite: () -> Unit,
  /** `Detect tone` was tapped: read the tone of [text], the text the sheet is showing (#32). */
  val onDetectTone: (text: String) -> Unit = {},
  /** Undo: put the field back the way it was before the overlay's last change. */
  val onUndo: () -> Unit = {},
)

/**
 * The floating assistant: a bubble that becomes a pill when there is something to fix, and opens
 * into a sheet docked above the keyboard where the fixes are reviewed one at a time.
 *
 * Only the collapsed forms are draggable. The sheet is anchored to the bottom of the screen by the
 * host, so there is nowhere for it to be dragged to, and a drag there would fight the buttons.
 * Styled by [AppTheme] so it matches the app.
 *
 * [tone] is the tone detector's latest read (#32) and [autoDetectTone] the "Detect tone while
 * typing" setting; together they decide what the docked sheets say about the tone (see
 * [toneStripState]).
 */
@Composable
fun SuggestionOverlay(
  state: SuggestionOverlayState,
  actions: SuggestionOverlayActions,
  darkTheme: Boolean = true,
  tone: ToneCheck? = null,
  autoDetectTone: Boolean = false,
  canUndo: Boolean = false,
) {
  AppTheme(darkTheme = darkTheme) {
    val docked = state.isDocked
    val dragModifier =
      if (docked) Modifier
      else
        Modifier.pointerInput(actions) {
          detectDragGestures(
            onDragStart = { actions.onDragStart() },
            onDragEnd = { actions.onDragEnd() },
            onDragCancel = { actions.onDragCancel() },
          ) { change, amount ->
            change.consume()
            actions.onDrag(amount)
          }
        }
    val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val slide = MaterialTheme.motionScheme.fastSpatialSpec<IntOffset>()
    AnimatedContent(
      targetState = state,
      contentKey = { it::class to (it as? SuggestionOverlayState.Review)?.open },
      transitionSpec = {
        val sliding = targetState.isDocked || initialState.isDocked
        ContentTransform(
          // The sheet arrives from and leaves towards the screen edge it is docked against. A
          // short slide: the pill and the sheet are very different sizes, and a long one reads
          // as the overlay being slow rather than as it arriving.
          targetContentEnter =
            if (sliding) fadeIn(fade) + slideInVertically(slide) { it / 6 } else fadeIn(fade),
          initialContentExit =
            if (sliding) fadeOut(fade) + slideOutVertically(slide) { it / 6 } else fadeOut(fade),
          // No size animation. Every one of these transitions changes the overlay's size sharply
          // — a 340dp pill becomes a full-width sheet — and springing between the two is what
          // made opening the sheet feel slow. The size snaps; only opacity and offset animate.
          sizeTransform = null,
        )
      },
      // Room for the shadow, which the window would otherwise clip. A docked sheet has no gap to
      // leave: it is flush with the bottom and both side edges.
      modifier = dragModifier.padding(if (docked) 0.dp else 8.dp),
    ) { current ->
      // The text the tone line speaks for: the one the sheet shows, or for the all-clear sheet,
      // which shows none, the one the detector last saw.
      val toneText =
        when (current) {
          is SuggestionOverlayState.Review -> current.scopedText
          is SuggestionOverlayState.Rewrite -> current.original
          SuggestionOverlayState.AllClear -> tone?.text
          else -> null
        }
      val strip = toneStripState(tone, toneText, autoDetectTone)
      val onDetect: () -> Unit = { toneText?.let(actions.onDetectTone) }
      when (current) {
        SuggestionOverlayState.Idle -> IdleBubble(actions, canUndo)
        SuggestionOverlayState.Checking -> CheckingBubble(actions, canUndo)
        SuggestionOverlayState.Working -> WorkingSheet(actions)
        SuggestionOverlayState.AllClear -> AllClearSheet(actions, strip, onDetect, canUndo)
        is SuggestionOverlayState.Error -> ErrorPill(current.message)
        is SuggestionOverlayState.Review ->
          if (current.open) ReviewSheet(current, actions, strip, onDetect, canUndo)
          else ReviewPill(current, actions, canUndo)
        is SuggestionOverlayState.Rewrite -> RewritePanel(current, actions, strip, onDetect)
      }
    }
  }
}

/** Diameter of the small, see-through bubble that waits while there is nothing to fix. */
private val SmallBubbleDiameter: Dp = 32.dp

/** How see-through the waiting bubble is, so it stays out of the way of the app beneath. */
private const val IdleBubbleAlpha = 0.55f

/**
 * Nothing to suggest, so the bubble shrinks to a small, see-through target that stays out of the
 * way. A tap offers the next useful thing: rewriting the text, or — with no field at all — a
 * shortcut into the app ([SuggestionOverlayActions.onBubbleTap]). A drag past touch slop is
 * consumed by the overlay's own drag handler, which cancels the press, so moving the bubble never
 * does either.
 */
@Composable
private fun IdleBubble(actions: SuggestionOverlayActions, canUndo: Boolean) {
  val openLabel = stringResource(Res.string.overlay_bubble_tap)
  Row(verticalAlignment = Alignment.CenterVertically) {
    Surface(
      onClick = actions.onBubbleTap,
      shape = CircleShape,
      color = MaterialTheme.colorScheme.primaryContainer,
      contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
      shadowElevation = 2.dp,
      // `Surface(onClick)` already reports a button; this names what the button does.
      modifier =
        Modifier.graphicsLayer { alpha = IdleBubbleAlpha }
          .semantics { onClick(label = openLabel, action = null) },
    ) {
      Box(Modifier.size(SmallBubbleDiameter), contentAlignment = Alignment.Center) {
        Icon(
          Icons.Rounded.AutoFixHigh,
          contentDescription = stringResource(Res.string.overlay_listening),
          modifier = Modifier.size(18.dp),
        )
      }
    }
    if (canUndo) UndoChip(actions)
  }
}

/**
 * The user typed and the model is checking the text: the same small target, turned into an
 * expressive loading indicator. It only grows into the pill once there is something to fix.
 */
@Composable
private fun CheckingBubble(actions: SuggestionOverlayActions, canUndo: Boolean) {
  val checking = stringResource(Res.string.overlay_checking)
  Row(verticalAlignment = Alignment.CenterVertically) {
    Surface(
      shape = CircleShape,
      color = MaterialTheme.colorScheme.primaryContainer,
      shadowElevation = 2.dp,
      modifier = Modifier.semantics { contentDescription = checking },
    ) {
      Box(Modifier.size(SmallBubbleDiameter), contentAlignment = Alignment.Center) {
        LoadingIndicator(
          color = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.size(28.dp),
        )
      }
    }
    if (canUndo) UndoChip(actions)
  }
}

/** Undo for the collapsed forms: a small round button beside the bubble or pill. */
@Composable
private fun UndoChip(actions: SuggestionOverlayActions) {
  Spacer(Modifier.width(6.dp))
  Surface(
    onClick = actions.onUndo,
    shape = CircleShape,
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor = MaterialTheme.colorScheme.onSurface,
    shadowElevation = 4.dp,
  ) {
    Box(Modifier.size(SmallBubbleDiameter), contentAlignment = Alignment.Center) {
      Icon(
        Icons.AutoMirrored.Rounded.Undo,
        contentDescription = stringResource(Res.string.overlay_undo),
        modifier = Modifier.size(18.dp),
      )
    }
  }
}

/**
 * Collapsed state: there are fixes. The count opens the sheet with all of them; beside it is one
 * fix at a time — what it puts in — which a tap accepts, and a bin that dismisses it. Each one
 * handled brings the next into view.
 */
@Composable
private fun ReviewPill(
  state: SuggestionOverlayState.Review,
  actions: SuggestionOverlayActions,
  canUndo: Boolean,
) {
  val suggestion = state.current ?: return
  val openLabel = stringResource(Res.string.overlay_open_sheet)
  val acceptLabel = stringResource(Res.string.overlay_accept_one)
  Row(verticalAlignment = Alignment.CenterVertically) {
    Surface(
      shape = CircleShape,
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      contentColor = MaterialTheme.colorScheme.onSurface,
      shadowElevation = 8.dp,
      modifier = Modifier.widthIn(max = 300.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(4.dp),
      ) {
        Surface(
          onClick = actions.onOpenSheet,
          shape = CircleShape,
          color = MaterialTheme.colorScheme.error,
          contentColor = MaterialTheme.colorScheme.onError,
          modifier = Modifier.semantics { onClick(label = openLabel, action = null) },
        ) {
          Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Text(
              text = state.items.size.toString(),
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.Bold,
            )
          }
        }
        Surface(
          onClick = { actions.onAccept(state.index) },
          shape = CircleShape,
          color = MaterialTheme.colorScheme.surfaceContainerHigh,
          modifier =
            Modifier.weight(1f, fill = false).semantics {
              onClick(label = acceptLabel, action = null)
            },
        ) {
          Column(Modifier.padding(horizontal = 10.dp, vertical = 2.dp)) {
            Text(
              text = suggestion.label(),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = suggestion.replacement.ifEmpty { suggestion.original },
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.SemiBold,
              color =
                if (suggestion.replacement.isEmpty()) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        IconButton(
          shapes = IconButtonDefaults.shapes(),
          onClick = { actions.onDismiss(state.index) },
          modifier = Modifier.size(36.dp),
        ) {
          Icon(
            Icons.Rounded.DeleteOutline,
            contentDescription = stringResource(Res.string.overlay_dismiss),
            modifier = Modifier.size(20.dp),
          )
        }
      }
    }
    if (canUndo) UndoChip(actions)
  }
}

/** Diameter of the drop-to-close target. */
val DismissTargetDiameter: Dp = 64.dp

/** Gap between the bottom of the screen and the bottom of the close target. */
val DismissTargetBottomMargin: Dp = 56.dp

/** How much the target grows once it has caught the overlay. */
private const val DismissTargetArmedScale = 1.25f

/**
 * Room reserved around the resting circle for the armed growth and its shadow.
 *
 * The growth is a `graphicsLayer` scale, which draws larger without laying out larger, so the
 * host's wrap-content window would otherwise be measured for the resting circle and clip the grown
 * one. [DismissTarget] takes this back out of its bottom padding, so reserving it leaves the
 * target's centre exactly where [DismissTargetBottomMargin] puts it and the drop geometry in
 * `DragToDismiss` unchanged.
 */
private val DismissTargetArmedOverflow: Dp =
  DismissTargetDiameter * (DismissTargetArmedScale - 1f) / 2f + 8.dp

/**
 * How near the target's centre the dragged overlay's centre has to come before the target catches
 * it. Deliberately wider than the target itself: the finger covers the bubble, so the drop has to
 * forgive aim.
 */
val DismissTargetCaptureRadius: Dp = 96.dp

/**
 * The drop-to-close target (issue #22), shown at the bottom of the screen for as long as the
 * overlay is being dragged. [armed] means the overlay has been caught by it, so letting go now
 * closes the assistant — the target grows and turns red to say so before the user commits.
 *
 * The host draws this in its own full-width, untouchable window: the overlay's own window only
 * wraps the bubble, so it cannot reach the bottom of the screen.
 */
@Composable
fun DismissTarget(armed: Boolean) {
  AppTheme {
    val scale by
      animateFloatAsState(
        targetValue = if (armed) DismissTargetArmedScale else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
      )
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .padding(bottom = DismissTargetBottomMargin - DismissTargetArmedOverflow),
      contentAlignment = Alignment.BottomCenter,
    ) {
      Box(
        Modifier.size(DismissTargetDiameter + DismissTargetArmedOverflow * 2),
        contentAlignment = Alignment.Center,
      ) {
        Surface(
          shape = CircleShape,
          color =
            if (armed) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.surfaceContainerHighest,
          contentColor =
            if (armed) MaterialTheme.colorScheme.onError
            else MaterialTheme.colorScheme.onSurfaceVariant,
          shadowElevation = 8.dp,
          modifier =
            Modifier.size(DismissTargetDiameter).graphicsLayer {
              scaleX = scale
              scaleY = scale
            },
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              Icons.Rounded.Close,
              contentDescription = stringResource(Res.string.overlay_close_assistant),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ErrorPill(message: String) {
  Surface(
    shape = CircleShape,
    color = MaterialTheme.colorScheme.errorContainer,
    contentColor = MaterialTheme.colorScheme.onErrorContainer,
    shadowElevation = 8.dp,
    modifier = Modifier.widthIn(max = 340.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
      Icon(Icons.Rounded.Warning, contentDescription = null)
      Spacer(Modifier.width(12.dp))
      Column {
        Text(
          text = stringResource(Res.string.overlay_model_unavailable),
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Text(text = message, style = MaterialTheme.typography.bodySmall, maxLines = 2)
      }
    }
  }
}
