@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.raghavsatyadev.grammarfix.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.BusinessCenter
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.raghavsatyadev.grammarfix.engine.DefaultTonePresets
import com.raghavsatyadev.grammarfix.engine.DiffItem
import com.raghavsatyadev.grammarfix.engine.DiffType
import com.raghavsatyadev.grammarfix.engine.TonePreset
import com.raghavsatyadev.grammarfix.ui.tone.toneName
import grammarfix.composeapp.generated.resources.Res
import grammarfix.composeapp.generated.resources.overlay_dismiss
import grammarfix.composeapp.generated.resources.overlay_rewrite_back
import grammarfix.composeapp.generated.resources.overlay_rewrite_failed
import grammarfix.composeapp.generated.resources.overlay_rewrite_instruction_hint
import grammarfix.composeapp.generated.resources.overlay_rewrite_instruction_send
import grammarfix.composeapp.generated.resources.overlay_rewrite_loading
import grammarfix.composeapp.generated.resources.overlay_rewrite_title_custom
import grammarfix.composeapp.generated.resources.overlay_rewrite_title_elaborate
import grammarfix.composeapp.generated.resources.overlay_rewrite_title_formal
import grammarfix.composeapp.generated.resources.overlay_rewrite_title_friendly
import grammarfix.composeapp.generated.resources.overlay_rewrite_title_instruction
import grammarfix.composeapp.generated.resources.overlay_rewrite_title_polish
import grammarfix.composeapp.generated.resources.overlay_rewrite_title_rephrase
import grammarfix.composeapp.generated.resources.overlay_rewrite_title_shorten
import grammarfix.composeapp.generated.resources.overlay_rewrite_unchanged
import grammarfix.composeapp.generated.resources.overlay_rewrite_use
import org.jetbrains.compose.resources.stringResource

/**
 * The rewrite area's fixed height. The title, the skeleton and the answer all share it, so a chip
 * tap swaps the text inside the panel and never changes the panel's size (#31) — which would
 * otherwise jump twice per rewrite, once into loading and once out of it, over the user's keyboard.
 */
private val RewriteBodyHeight = 152.dp

/**
 * The rewrite panel (#31): the field's whole text rewritten in one tone, with every changed span
 * tinted, a chip row to switch tones in place, and a field for a one-off instruction below.
 *
 * It shares [OverlaySheet] with the review sheet it opens from, so to the user it is the same sheet
 * showing something else.
 */
@Composable
internal fun RewritePanel(
  state: SuggestionOverlayState.Rewrite,
  actions: SuggestionOverlayActions,
  tone: ToneStripState = ToneStripState.Hidden,
  onDetectTone: () -> Unit = {},
) {
  Column {
    OverlaySheet(
      // Pulling the panel down goes back, as its back arrow does.
      onDismiss = actions.onCloseRewrite,
      header = {
        IconButton(shapes = IconButtonDefaults.shapes(), onClick = actions.onCloseRewrite) {
          Icon(
            Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(Res.string.overlay_rewrite_back),
          )
        }
      },
    ) {
      Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().height(RewriteBodyHeight),
      ) {
        when (val result = state.result) {
          RewriteResult.Loading -> RewriteSkeleton()
          is RewriteResult.Ready -> {
            val unchanged = result.text.trim() == state.original.trim()
            RewriteTitle(
              if (unchanged) stringResource(Res.string.overlay_rewrite_unchanged)
              else rewriteTitle(state)
            )
            Text(
              text = rewriteWithChanges(result.text, result.diffs),
              style = MaterialTheme.typography.bodyLarge,
              modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            )
          }
          is RewriteResult.Failed -> {
            RewriteTitle(rewriteTitle(state))
            Text(
              text = stringResource(Res.string.overlay_rewrite_failed, result.message),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.error,
            )
          }
        }
      }

      // How the original reads (#32), just above the chips that change it.
      ToneStrip(tone, onDetectTone)

      ToneChips(state, actions)

      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
      ) {
        TextButton(shapes = ButtonDefaults.shapes(), onClick = actions.onCloseRewrite) {
          Text(stringResource(Res.string.overlay_dismiss))
        }
        val result = state.result
        Button(
          shapes = ButtonDefaults.shapes(),
          onClick = actions.onUseRewrite,
          enabled = result is RewriteResult.Ready && result.text.trim() != state.original.trim(),
        ) {
          Text(stringResource(Res.string.overlay_rewrite_use))
        }
      }
    }

    InstructionBar(actions)
  }
}

@Composable
private fun RewriteTitle(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleMedium,
    fontWeight = FontWeight.Bold,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

/** Stands in for the title and the text while the model runs — most of what the user sees. */
@Composable
private fun RewriteSkeleton() {
  val loading = stringResource(Res.string.overlay_rewrite_loading)
  Column(
    verticalArrangement = Arrangement.spacedBy(10.dp),
    modifier =
      Modifier.fillMaxWidth().padding(top = 4.dp).semantics { contentDescription = loading },
  ) {
    SkeletonBar(widthFraction = 0.6f, height = 18.dp)
    Spacer(Modifier.height(2.dp))
    SkeletonBar(widthFraction = 1f)
    SkeletonBar(widthFraction = 0.94f)
    SkeletonBar(widthFraction = 0.88f)
    SkeletonBar(widthFraction = 0.5f)
  }
}

/**
 * One chip per tone, scrolling sideways. The selected chip takes the accent outline and label;
 * while a typed instruction is showing, none is selected.
 */
@Composable
private fun ToneChips(state: SuggestionOverlayState.Rewrite, actions: SuggestionOverlayActions) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
  ) {
    state.tones.forEach { tone ->
      val selected = state.instruction == null && tone.id == state.selectedId
      FilterChip(
        selected = selected,
        onClick = { actions.onSelectTone(tone.id) },
        label = { Text(toneName(tone)) },
        leadingIcon = {
          Icon(
            tone.icon(),
            contentDescription = null,
            modifier = Modifier.size(FilterChipDefaults.IconSize),
          )
        },
        colors =
          FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            selectedLabelColor = MaterialTheme.colorScheme.primary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
          ),
        border =
          FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            selectedBorderColor = MaterialTheme.colorScheme.primary,
            selectedBorderWidth = 1.5.dp,
          ),
      )
    }
  }
}

/**
 * A free-text instruction — "Make it sound professional" — for a tone the chip row doesn't have. On
 * its own surface below the sheet, as Grammarly lays it out.
 *
 * The draft lives here rather than in the overlay's state: it is the user's own words, and nothing
 * outside this field needs them until they are sent.
 */
@Composable
private fun InstructionBar(actions: SuggestionOverlayActions) {
  var draft by rememberSaveable { mutableStateOf("") }
  var focused by remember { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  val windowFocused = LocalWindowInfo.current.isWindowFocused
  // The overlay's window only takes input focus once this field asks for it (onInstructionFocus),
  // so the keyboard is requested when that focus actually arrives. Losing it again — the user
  // tapped back into their own app — ends the field's focus too; otherwise the next tap on a field
  // that still thinks it is focused would never ask for the window again.
  var hadWindow by remember { mutableStateOf(false) }
  LaunchedEffect(focused, windowFocused) {
    when {
      !focused -> hadWindow = false
      windowFocused -> {
        hadWindow = true
        keyboard?.show()
      }
      hadWindow -> focusManager.clearFocus()
    }
  }
  val send = {
    if (draft.isNotBlank()) {
      actions.onSendInstruction(draft)
      draft = ""
      focusManager.clearFocus()
    }
  }
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainer,
    modifier = Modifier.fillMaxWidth(),
  ) {
    OutlinedTextField(
      value = draft,
      onValueChange = { draft = it },
      placeholder = { Text(stringResource(Res.string.overlay_rewrite_instruction_hint)) },
      singleLine = true,
      shape = CircleShape,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
      keyboardActions = KeyboardActions(onSend = { send() }),
      trailingIcon = {
        IconButton(
          shapes = IconButtonDefaults.shapes(),
          onClick = send,
          enabled = draft.isNotBlank(),
        ) {
          Icon(
            Icons.AutoMirrored.Rounded.Send,
            contentDescription = stringResource(Res.string.overlay_rewrite_instruction_send),
          )
        }
      },
      colors =
        OutlinedTextFieldDefaults.colors(
          unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
        ),
      modifier =
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).onFocusChanged {
          if (it.isFocused != focused) {
            focused = it.isFocused
            actions.onInstructionFocus(it.isFocused)
          }
        },
    )
  }
}

/** Says what the rewrite on screen did, in the words of the chip or instruction behind it. */
@Composable
private fun rewriteTitle(state: SuggestionOverlayState.Rewrite): String {
  state.instruction?.let {
    return stringResource(Res.string.overlay_rewrite_title_instruction, it)
  }
  return when (state.selectedId) {
    DefaultTonePresets.POLISH_ID -> stringResource(Res.string.overlay_rewrite_title_polish)
    DefaultTonePresets.REPHRASE_ID -> stringResource(Res.string.overlay_rewrite_title_rephrase)
    DefaultTonePresets.SHORTEN_ID -> stringResource(Res.string.overlay_rewrite_title_shorten)
    DefaultTonePresets.FRIENDLY_ID -> stringResource(Res.string.overlay_rewrite_title_friendly)
    DefaultTonePresets.FORMAL_ID -> stringResource(Res.string.overlay_rewrite_title_formal)
    DefaultTonePresets.ELABORATE_ID -> stringResource(Res.string.overlay_rewrite_title_elaborate)
    else -> {
      val name = state.tones.firstOrNull { it.id == state.selectedId }?.let { toneName(it) }
      stringResource(Res.string.overlay_rewrite_title_custom, name.orEmpty())
    }
  }
}

private fun TonePreset.icon(): ImageVector =
  when (id) {
    DefaultTonePresets.POLISH_ID -> Icons.Rounded.AutoFixHigh
    DefaultTonePresets.REPHRASE_ID -> Icons.Rounded.Autorenew
    DefaultTonePresets.SHORTEN_ID -> Icons.AutoMirrored.Rounded.ShortText
    DefaultTonePresets.FRIENDLY_ID -> Icons.Rounded.SentimentSatisfied
    DefaultTonePresets.FORMAL_ID -> Icons.Rounded.BusinessCenter
    DefaultTonePresets.ELABORATE_ID -> Icons.AutoMirrored.Rounded.Notes
    else -> Icons.Rounded.Tune
  }

/** [text] with the spans [rewriteChanges] finds tinted in the theme's accent. */
@Composable
private fun rewriteWithChanges(text: String, diffs: List<DiffItem>): AnnotatedString {
  val changed =
    SpanStyle(
      color = MaterialTheme.colorScheme.onPrimaryContainer,
      background = MaterialTheme.colorScheme.primaryContainer,
    )
  val ranges = rewriteChanges(text, diffs)
  return buildAnnotatedString {
    var at = 0
    ranges.forEach { range ->
      append(text.substring(at, range.first))
      withStyle(changed) { append(text.substring(range.first, range.last + 1)) }
      at = range.last + 1
    }
    append(text.substring(at))
  }
}

/**
 * The character ranges of [rewritten] that the model changed, for tinting inline.
 *
 * [diffs] is [com.raghavsatyadev.grammarfix.engine.GrammarDiffAnalyzer]'s word alignment of the
 * original against [rewritten]. Every item except a deletion accounts for one word of [rewritten]
 * in order, so walking the two together places each replaced or inserted word exactly — in the
 * rewrite's own text, with its own spacing and line breaks, rather than in words re-joined by
 * single spaces.
 *
 * Neighbouring changed words merge into one range that takes the space between them too, so a
 * rewritten phrase reads as one tinted run rather than a row of separate blocks. A deletion leaves
 * nothing in the rewrite to tint.
 */
fun rewriteChanges(rewritten: String, diffs: List<DiffItem>): List<IntRange> {
  // Identical text comes back as one UNCHANGED item holding the whole of it, not a word list.
  if (diffs.none { it.type == DiffType.REPLACED || it.type == DiffType.INSERTED }) {
    return emptyList()
  }
  val words = WORD.findAll(rewritten).map { it.range }.toList()
  val ranges = mutableListOf<IntRange>()
  var k = 0
  for (diff in diffs) {
    if (diff.type == DiffType.DELETED) continue
    val word = words.getOrNull(k++) ?: break
    if (diff.type == DiffType.UNCHANGED) continue
    val last = ranges.lastOrNull()
    // Adjacent: the previous changed word was the one right before this one.
    if (last != null && words.getOrNull(k - 2)?.last == last.last) {
      ranges[ranges.lastIndex] = last.first..word.last
    } else {
      ranges += word
    }
  }
  return ranges
}

/** Same word boundaries [com.raghavsatyadev.grammarfix.engine.GrammarDiffAnalyzer] splits on. */
private val WORD = Regex("\\S+")
