@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.raghavsatyadev.grammarfix.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.raghavsatyadev.grammarfix.engine.SuggestionKind
import com.raghavsatyadev.grammarfix.engine.TextSuggestion
import com.raghavsatyadev.grammarfix.engine.kind
import grammarfix.composeapp.generated.resources.Res
import grammarfix.composeapp.generated.resources.overlay_accept
import grammarfix.composeapp.generated.resources.overlay_accept_all
import grammarfix.composeapp.generated.resources.overlay_all_clear_body
import grammarfix.composeapp.generated.resources.overlay_all_clear_title
import grammarfix.composeapp.generated.resources.overlay_brand
import grammarfix.composeapp.generated.resources.overlay_close_sheet
import grammarfix.composeapp.generated.resources.overlay_dismiss
import grammarfix.composeapp.generated.resources.overlay_kind_add
import grammarfix.composeapp.generated.resources.overlay_kind_remove
import grammarfix.composeapp.generated.resources.overlay_kind_replace
import grammarfix.composeapp.generated.resources.overlay_open_rewrite
import grammarfix.composeapp.generated.resources.overlay_review_position
import grammarfix.composeapp.generated.resources.overlay_review_title
import grammarfix.composeapp.generated.resources.overlay_selection_all_clear
import grammarfix.composeapp.generated.resources.overlay_selection_title
import grammarfix.composeapp.generated.resources.overlay_undo
import grammarfix.composeapp.generated.resources.overlay_working_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The sheet the overlay opens into: every suggestion at once, each read inside the sentence it
 * belongs to, with the words it takes out struck through and the words it puts in highlighted, and
 * its own Accept and Dismiss.
 *
 * Opened over a selection ([SuggestionOverlayState.Review.scope]), it lists only the fixes inside
 * it. The host docks this to the bottom of the screen, so it never covers the field being edited.
 * [OverlaySheet] is the chrome every panel shares — the rewrite panel in #31 opens inside the same
 * frame, so the two never appear to be different windows.
 */
@Composable
internal fun ReviewSheet(
  state: SuggestionOverlayState.Review,
  actions: SuggestionOverlayActions,
  tone: ToneStripState = ToneStripState.Hidden,
  onDetectTone: () -> Unit = {},
  canUndo: Boolean = false,
) {
  val visible = state.visible
  if (visible.isEmpty()) {
    // Nothing to fix inside the selection, though the rest of the text may still have some.
    AllClearSheet(actions, tone, onDetectTone, canUndo, inSelection = state.scope != null)
    return
  }
  OverlaySheet(
    onDismiss = actions.onCloseSheet,
    header = {
      if (canUndo) UndoButton(actions)
      RewriteButton(actions)
      if (visible.size > 1) {
        IconButton(shapes = IconButtonDefaults.shapes(), onClick = actions.onAcceptAll) {
          Icon(
            Icons.Rounded.DoneAll,
            contentDescription = stringResource(Res.string.overlay_accept_all, visible.size),
          )
        }
      }
      IconButton(shapes = IconButtonDefaults.shapes(), onClick = actions.onCloseSheet) {
        Icon(
          Icons.Rounded.ExpandMore,
          contentDescription = stringResource(Res.string.overlay_close_sheet),
        )
      }
    },
  ) {
    Text(
      text =
        stringResource(
          if (state.scope != null) Res.string.overlay_selection_title
          else Res.string.overlay_review_title
        ),
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
    )

    ToneStrip(tone, onDetectTone)

    Column(
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
    ) {
      visible.forEachIndexed { position, (index, suggestion) ->
        if (position > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SuggestionRow(state.text, suggestion, position + 1, visible.size, index, actions)
      }
    }
  }
}

/** One fix in the sheet's list: where it sits, the fix in its sentence, and its two buttons. */
@Composable
private fun SuggestionRow(
  text: String,
  suggestion: TextSuggestion,
  position: Int,
  total: Int,
  index: Int,
  actions: SuggestionOverlayActions,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    SuggestionHeadline(suggestion, position = position, total = total)
    Text(text = suggestionInContext(text, suggestion), style = MaterialTheme.typography.bodyLarge)
    Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth(),
    ) {
      TextButton(shapes = ButtonDefaults.shapes(), onClick = { actions.onDismiss(index) }) {
        Text(stringResource(Res.string.overlay_dismiss))
      }
      Button(shapes = ButtonDefaults.shapes(), onClick = { actions.onAccept(index) }) {
        Text(stringResource(Res.string.overlay_accept))
      }
    }
  }
}

/**
 * Nothing left to review, said where the last suggestion was, so the sheet does not just vanish.
 * [inSelection] says it of the selected text only.
 */
@Composable
internal fun AllClearSheet(
  actions: SuggestionOverlayActions,
  tone: ToneStripState = ToneStripState.Hidden,
  onDetectTone: () -> Unit = {},
  canUndo: Boolean = false,
  inSelection: Boolean = false,
) {
  OverlaySheet(
    onDismiss = actions.onCloseSheet,
    header = {
      if (canUndo) UndoButton(actions)
      RewriteButton(actions)
      IconButton(shapes = IconButtonDefaults.shapes(), onClick = actions.onCloseSheet) {
        Icon(
          Icons.Rounded.ExpandMore,
          contentDescription = stringResource(Res.string.overlay_close_sheet),
        )
      }
    },
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
    ) {
      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
      ) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
          Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(32.dp))
        }
      }
      Text(
        text = stringResource(Res.string.overlay_all_clear_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text =
          stringResource(
            if (inSelection) Res.string.overlay_selection_all_clear
            else Res.string.overlay_all_clear_body
          ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      ToneStrip(tone, onDetectTone)
    }
  }
}

/** The header's Undo: puts the field back the way it was before the overlay's last change. */
@Composable
private fun UndoButton(actions: SuggestionOverlayActions) {
  IconButton(shapes = IconButtonDefaults.shapes(), onClick = actions.onUndo) {
    Icon(
      Icons.AutoMirrored.Rounded.Undo,
      contentDescription = stringResource(Res.string.overlay_undo),
    )
  }
}

/**
 * A fix has just been applied and the text is being checked again. Inference takes tens of seconds
 * on device, so the sheet says what it is doing and holds its place rather than closing and
 * reopening — the bars stand in for the sentence that is coming.
 */
@Composable
internal fun WorkingSheet(actions: SuggestionOverlayActions) {
  OverlaySheet(
    onDismiss = actions.onCloseSheet,
    header = {
      IconButton(shapes = IconButtonDefaults.shapes(), onClick = actions.onCloseSheet) {
        Icon(
          Icons.Rounded.ExpandMore,
          contentDescription = stringResource(Res.string.overlay_close_sheet),
        )
      }
    },
  ) {
    Text(
      text = stringResource(Res.string.overlay_working_title),
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(4.dp))
    SkeletonBar(widthFraction = 1f)
    SkeletonBar(widthFraction = 0.92f)
    SkeletonBar(widthFraction = 0.55f)
    Spacer(Modifier.height(24.dp))
  }
}

/**
 * The header's sparkle: opens the rewrite panel (#31) over the field's whole text. Offered from
 * every sheet that has settled on a text — a clean one can still be made friendlier or shorter.
 */
@Composable
private fun RewriteButton(actions: SuggestionOverlayActions) {
  IconButton(shapes = IconButtonDefaults.shapes(), onClick = actions.onOpenRewrite) {
    Icon(
      Icons.Rounded.AutoAwesome,
      contentDescription = stringResource(Res.string.overlay_open_rewrite),
    )
  }
}

@Composable
internal fun SkeletonBar(widthFraction: Float, height: Dp = 14.dp) {
  Surface(
    shape = CircleShape,
    color = MaterialTheme.colorScheme.surfaceContainerHighest,
    modifier = Modifier.fillMaxWidth(widthFraction),
    content = { Box(Modifier.height(height)) },
  )
}

/**
 * The room a docked sheet takes at the bottom of the screen. The host sets it to the keyboard the
 * sheet stands in for, so the sheet covers the keyboard's exact place and the screen above does not
 * move.
 *
 * [minHeight] is the keyboard's height, navigation bar included. [bottomInset] is the navigation
 * bar, which the sheet now covers and must keep its content clear of. Both are zero when no
 * keyboard was seen, or when the keyboard is up below the sheet.
 */
@Immutable data class DockSpace(val minHeight: Dp = 0.dp, val bottomInset: Dp = 0.dp)

val LocalDockSpace = staticCompositionLocalOf { DockSpace() }

/**
 * The frame every overlay panel shares: a grab handle, a row carrying the app's name and whatever
 * [header] actions the panel needs, a divider, and the panel's own [content].
 *
 * Only the top corners are rounded: the host docks this against the bottom edge of the screen.
 *
 * The handle and header row close the panel through [onDismiss]: drag them down past
 * [SheetDismissDistance] or fling them down, or tap the handle. A shorter drag springs back. Only
 * the top part listens, so a scrolling list in [content] never closes the panel by mistake.
 */
@Composable
internal fun OverlaySheet(
  header: @Composable RowScope.() -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  val scope = rememberCoroutineScope()
  val offset = remember { Animatable(0f) }
  var heightPx by remember { mutableIntStateOf(0) }
  val density = LocalDensity.current
  val dismissPx = with(density) { SheetDismissDistance.toPx() }
  val flingPx = with(density) { SheetDismissVelocity.toPx() }
  val spring = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
  // Slides the sheet off the bottom edge first, so it leaves the way the finger sent it.
  val slideAway: () -> Unit = {
    scope.launch {
      offset.animateTo(heightPx.toFloat(), spring)
      onDismiss()
      offset.snapTo(0f)
    }
  }
  val dragState = rememberDraggableState { delta ->
    scope.launch { offset.snapTo((offset.value + delta).coerceAtLeast(0f)) }
  }
  val closeLabel = stringResource(Res.string.overlay_close_sheet)
  val space = LocalDockSpace.current

  Surface(
    shape =
      MaterialTheme.shapes.extraLarge.copy(
        bottomStart = ZeroCornerSize,
        bottomEnd = ZeroCornerSize,
      ),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor = MaterialTheme.colorScheme.onSurface,
    shadowElevation = 8.dp,
    modifier =
      modifier
        .fillMaxWidth()
        .heightIn(min = space.minHeight)
        .onSizeChanged { heightPx = it.height }
        .graphicsLayer { translationY = offset.value },
  ) {
    Column(Modifier.padding(bottom = space.bottomInset)) {
      Column(
        Modifier.draggable(
          state = dragState,
          orientation = Orientation.Vertical,
          onDragStopped = { velocity ->
            if (offset.value > dismissPx || velocity > flingPx) slideAway()
            else offset.animateTo(0f, spring)
          },
        )
      ) {
        Box(
          Modifier.fillMaxWidth()
            .clickable(onClickLabel = closeLabel, role = Role.Button, onClick = slideAway)
            .padding(vertical = 12.dp),
          contentAlignment = Alignment.Center,
        ) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.outlineVariant,
            content = { Box(Modifier.size(width = 32.dp, height = 4.dp)) },
          )
        }

        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 4.dp),
        ) {
          Icon(
            Icons.Rounded.AutoFixHigh,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
          )
          Spacer(Modifier.width(8.dp))
          Text(
            text = stringResource(Res.string.overlay_brand),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
          )
          header()
        }
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

      Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
        content = content,
      )
    }
  }
}

/** How far the handle has to be dragged down before letting go closes the sheet. */
private val SheetDismissDistance = 64.dp

/** A downward fling at least this fast closes the sheet however short the drag was, per second. */
private val SheetDismissVelocity = 800.dp

/**
 * What this fix does and where it sits in the queue — "2 of 5 · Replace “teh”".
 *
 * The dot is the only thing that carries the kind at a glance, so it takes the colour the fix is
 * drawn in further down: the same green for words going in, the error colour for words coming out.
 */
@Composable
private fun SuggestionHeadline(suggestion: TextSuggestion, position: Int, total: Int) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Surface(shape = CircleShape, color = suggestion.dotColor()) {
      Box(Modifier.size(8.dp))
    }
    Spacer(Modifier.width(8.dp))
    Text(
      text =
        stringResource(Res.string.overlay_review_position, position, total) +
          " · " +
          suggestion.label(),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun TextSuggestion.dotColor(): Color =
  when (kind) {
    SuggestionKind.REMOVE -> MaterialTheme.colorScheme.error
    SuggestionKind.ADD -> MaterialTheme.colorScheme.tertiary
    SuggestionKind.REPLACE -> MaterialTheme.colorScheme.primary
  }

@Composable
internal fun TextSuggestion.label(): String =
  when (kind) {
    SuggestionKind.ADD -> stringResource(Res.string.overlay_kind_add)
    SuggestionKind.REMOVE -> stringResource(Res.string.overlay_kind_remove)
    SuggestionKind.REPLACE -> stringResource(Res.string.overlay_kind_replace, original)
  }

/** The theme colours [suggestionInContext] draws the two halves of a fix in. */
@Composable
private fun suggestionInContext(text: String, suggestion: TextSuggestion): AnnotatedString =
  suggestionInContext(
    text = text,
    suggestion = suggestion,
    removed =
      SpanStyle(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        background = MaterialTheme.colorScheme.surfaceContainerHighest,
        textDecoration = TextDecoration.LineThrough,
      ),
    added =
      SpanStyle(
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        background = MaterialTheme.colorScheme.primaryContainer,
        fontWeight = FontWeight.SemiBold,
      ),
  )

/**
 * Renders [suggestion] inside the sentence it was found in: the words it takes out struck through,
 * the words it puts in immediately after, and enough of [text] on either side to recognise where
 * the fix lands. A fix read this way needs no mental diff.
 *
 * Long text is cut down to [window] characters on each side and marked with an ellipsis, snapped to
 * a word boundary so the context never starts mid-word. An insertion has nothing to strike and a
 * deletion has nothing to add, so either half can be absent.
 */
fun suggestionInContext(
  text: String,
  suggestion: TextSuggestion,
  removed: SpanStyle,
  added: SpanStyle,
  window: Int = 90,
): AnnotatedString {
  val start = suggestion.startIndex.coerceIn(0, text.length)
  val end = suggestion.endIndex.coerceIn(start, text.length)

  return buildAnnotatedString {
    append(text.take(start).tail(window))
    // `original` rather than the spanned text: a deletion's range swallows the following space
    // too, and striking that through would leave a gap the user cannot account for.
    if (suggestion.original.isNotEmpty()) withStyle(removed) { append(suggestion.original) }
    if (suggestion.replacement.isNotEmpty()) {
      if (suggestion.original.isNotEmpty()) append(" ")
      withStyle(added) { append(suggestion.replacement) }
    }
    append(text.substring(end).head(window))
  }
}

/** The last [window] characters, cut at a word boundary and marked with a leading ellipsis. */
private fun String.tail(window: Int): String {
  if (length <= window) return this
  val cut = substring(length - window)
  val boundary = cut.indexOf(' ')
  return "…" + if (boundary == -1) cut else cut.substring(boundary)
}

/** The first [window] characters, cut at a word boundary and marked with a trailing ellipsis. */
private fun String.head(window: Int): String {
  if (length <= window) return this
  val cut = substring(0, window)
  val boundary = cut.lastIndexOf(' ')
  return (if (boundary == -1) cut else cut.substring(0, boundary)) + "…"
}
