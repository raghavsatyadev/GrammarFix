@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.raghavsatyadev.grammarfix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raghavsatyadev.grammarfix.engine.ToneCheck
import com.raghavsatyadev.grammarfix.engine.ToneStatus
import grammarfix.composeapp.generated.resources.Res
import grammarfix.composeapp.generated.resources.tone_detect_action
import grammarfix.composeapp.generated.resources.tone_detect_failed
import grammarfix.composeapp.generated.resources.tone_detecting
import grammarfix.composeapp.generated.resources.tone_label_apologetic
import grammarfix.composeapp.generated.resources.tone_label_casual
import grammarfix.composeapp.generated.resources.tone_label_direct
import grammarfix.composeapp.generated.resources.tone_label_formal
import grammarfix.composeapp.generated.resources.tone_label_friendly
import grammarfix.composeapp.generated.resources.tone_label_frustrated
import grammarfix.composeapp.generated.resources.tone_label_uncertain
import grammarfix.composeapp.generated.resources.tone_label_urgent
import grammarfix.composeapp.generated.resources.tone_reads_as
import org.jetbrains.compose.resources.stringResource

/** What the overlay says about the tone of the text it is showing (#32). */
@Immutable
sealed interface ToneStripState {
  /** Nothing to show: no text, or a read that will start by itself once typing settles. */
  data object Hidden : ToneStripState

  /** Tone detection while typing is off, so the user can ask for a read. */
  data object Offer : ToneStripState

  data object Detecting : ToneStripState

  data class Ready(val labels: List<String>, val summary: String) : ToneStripState

  data class Failed(val message: String) : ToneStripState
}

/**
 * The strip for [text], the text the sheet is showing, given the detector's latest [check].
 *
 * A check for different text says nothing about this one. With [autoDetect] on, a text with no
 * reading yet shows nothing, because one is on its way; with it off, it offers the button.
 */
fun toneStripState(check: ToneCheck?, text: String?, autoDetect: Boolean): ToneStripState {
  if (text.isNullOrBlank()) return ToneStripState.Hidden
  val status = check?.takeIf { it.text == text }?.status ?: ToneStatus.Idle
  return when (status) {
    ToneStatus.Idle -> if (autoDetect) ToneStripState.Hidden else ToneStripState.Offer
    ToneStatus.Detecting -> ToneStripState.Detecting
    is ToneStatus.Ready -> ToneStripState.Ready(status.reading.labels, status.reading.summary)
    is ToneStatus.Failed -> ToneStripState.Failed(status.message)
  }
}

/** The tone line in the overlay's sheets and above the rewrite panel's chips. */
@Composable
internal fun ToneStrip(state: ToneStripState, onDetect: () -> Unit, modifier: Modifier = Modifier) {
  when (state) {
    ToneStripState.Hidden -> Unit
    ToneStripState.Offer ->
      AssistChip(
        onClick = onDetect,
        label = { Text(stringResource(Res.string.tone_detect_action)) },
        leadingIcon = {
          Icon(
            Icons.Rounded.Mood,
            contentDescription = null,
            modifier = Modifier.size(AssistChipDefaults.IconSize),
          )
        },
        modifier = modifier,
      )
    ToneStripState.Detecting ->
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
      ) {
        LoadingIndicator(Modifier.size(24.dp))
        Text(
          text = stringResource(Res.string.tone_detecting),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    is ToneStripState.Ready -> ToneReadingView(state.labels, state.summary, modifier)
    is ToneStripState.Failed ->
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
      ) {
        Text(
          text = stringResource(Res.string.tone_detect_failed, state.message),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        TextButton(shapes = ButtonDefaults.shapes(), onClick = onDetect) {
          Text(stringResource(Res.string.tone_detect_action))
        }
      }
  }
}

/** Labels as small tonal pills, then the model's one-line summary. Shared with the Tones tab. */
@Composable
fun ToneReadingView(labels: List<String>, summary: String, modifier: Modifier = Modifier) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        text = stringResource(Res.string.tone_reads_as),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      labels.forEach { label ->
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
          Text(
            text = toneLabelName(label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }
      }
    }
    if (summary.isNotBlank()) {
      Text(
        text = summary,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** A label's display name. The ids are the model's closed vocabulary; this only translates them. */
@Composable
fun toneLabelName(id: String): String =
  when (id) {
    "formal" -> stringResource(Res.string.tone_label_formal)
    "casual" -> stringResource(Res.string.tone_label_casual)
    "friendly" -> stringResource(Res.string.tone_label_friendly)
    "direct" -> stringResource(Res.string.tone_label_direct)
    "uncertain" -> stringResource(Res.string.tone_label_uncertain)
    "urgent" -> stringResource(Res.string.tone_label_urgent)
    "apologetic" -> stringResource(Res.string.tone_label_apologetic)
    "frustrated" -> stringResource(Res.string.tone_label_frustrated)
    else -> id
  }
