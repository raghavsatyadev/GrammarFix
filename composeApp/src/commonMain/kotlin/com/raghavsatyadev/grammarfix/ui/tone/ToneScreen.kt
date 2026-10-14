@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.raghavsatyadev.grammarfix.ui.tone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raghavsatyadev.grammarfix.engine.DefaultTonePresets
import com.raghavsatyadev.grammarfix.engine.TonePreset
import com.raghavsatyadev.grammarfix.engine.hasEditedPrompt
import com.raghavsatyadev.grammarfix.ui.ToneReadingView
import com.raghavsatyadev.grammarfix.ui.components.SectionCard
import com.raghavsatyadev.grammarfix.ui.components.StatusPill
import com.raghavsatyadev.grammarfix.ui.components.StatusTone
import grammarfix.composeapp.generated.resources.Res
import grammarfix.composeapp.generated.resources.action_add_tone
import grammarfix.composeapp.generated.resources.action_cancel
import grammarfix.composeapp.generated.resources.action_delete
import grammarfix.composeapp.generated.resources.action_delete_tone
import grammarfix.composeapp.generated.resources.action_edit_tone
import grammarfix.composeapp.generated.resources.action_reset_prompt
import grammarfix.composeapp.generated.resources.action_save
import grammarfix.composeapp.generated.resources.tone_builtin_title
import grammarfix.composeapp.generated.resources.tone_custom_title
import grammarfix.composeapp.generated.resources.tone_delete_message
import grammarfix.composeapp.generated.resources.tone_detect_warning
import grammarfix.composeapp.generated.resources.tone_detect_while_typing_desc
import grammarfix.composeapp.generated.resources.tone_detect_while_typing_title
import grammarfix.composeapp.generated.resources.tone_detection_title
import grammarfix.composeapp.generated.resources.tone_edited
import grammarfix.composeapp.generated.resources.tone_editor_name
import grammarfix.composeapp.generated.resources.tone_editor_new
import grammarfix.composeapp.generated.resources.tone_editor_prompt
import grammarfix.composeapp.generated.resources.tone_elaborate
import grammarfix.composeapp.generated.resources.tone_formal
import grammarfix.composeapp.generated.resources.tone_friendly
import grammarfix.composeapp.generated.resources.tone_intro
import grammarfix.composeapp.generated.resources.tone_last_reading
import grammarfix.composeapp.generated.resources.tone_no_custom
import grammarfix.composeapp.generated.resources.tone_no_reading
import grammarfix.composeapp.generated.resources.tone_polish
import grammarfix.composeapp.generated.resources.tone_privacy_note
import grammarfix.composeapp.generated.resources.tone_rephrase
import grammarfix.composeapp.generated.resources.tone_shorten
import grammarfix.composeapp.generated.resources.tone_thinking_desc
import grammarfix.composeapp.generated.resources.tone_thinking_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** The tone's name: a translated string for the tones we ship, the user's own words otherwise. */
@Composable
fun toneName(preset: TonePreset): String =
  when (preset.id) {
    DefaultTonePresets.POLISH_ID -> stringResource(Res.string.tone_polish)
    DefaultTonePresets.FORMAL_ID -> stringResource(Res.string.tone_formal)
    DefaultTonePresets.ELABORATE_ID -> stringResource(Res.string.tone_elaborate)
    DefaultTonePresets.SHORTEN_ID -> stringResource(Res.string.tone_shorten)
    DefaultTonePresets.REPHRASE_ID -> stringResource(Res.string.tone_rephrase)
    DefaultTonePresets.FRIENDLY_ID -> stringResource(Res.string.tone_friendly)
    else -> preset.name
  }

/** Tones tab: choose the tone the editor rewrites with, reword its prompt, or write your own. */
@Composable
fun ToneScreen(
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
  viewModel: ToneViewModel = koinViewModel(),
) {
  val state by viewModel.uiState.collectAsState()
  ToneScreen(
    state = state,
    onEvent = viewModel::onEvent,
    contentPadding = contentPadding,
    modifier = modifier,
  )
}

@Composable
fun ToneScreen(
  state: ToneUiState,
  onEvent: (ToneEvent) -> Unit,
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
) {
  // A delete asks first: a custom prompt can be long, and there is no undo.
  var pendingDelete by remember { mutableStateOf<Pair<String, String>?>(null) }
  pendingDelete?.let { (id, name) ->
    DeleteToneDialog(
      name = name,
      onConfirm = {
        onEvent(ToneEvent.Delete(id))
        pendingDelete = null
      },
      onDismiss = { pendingDelete = null },
    )
  }
  Column(
    modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    ToneDetectionCard(state, onEvent)

    SectionCard(
      title = stringResource(Res.string.tone_builtin_title),
      icon = Icons.Rounded.Palette,
    ) {
      Text(
        text = stringResource(Res.string.tone_intro),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.selectableGroup(),
      ) {
        state.builtIn.forEach { preset ->
          TonePresetRow(
            preset = preset,
            isSelected = preset.id == state.selectedId,
            onSelect = { onEvent(ToneEvent.Select(preset.id)) },
            onEdit = { onEvent(ToneEvent.Edit(preset.id)) },
            onDelete = null,
          )
        }
      }
    }

    SectionCard(title = stringResource(Res.string.tone_custom_title), icon = Icons.Rounded.Edit) {
      if (state.custom.isEmpty()) {
        Text(
          text = stringResource(Res.string.tone_no_custom),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        Column(
          verticalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.selectableGroup(),
        ) {
          state.custom.forEach { preset ->
            TonePresetRow(
              preset = preset,
              isSelected = preset.id == state.selectedId,
              onSelect = { onEvent(ToneEvent.Select(preset.id)) },
              onEdit = { onEvent(ToneEvent.Edit(preset.id)) },
              onDelete = { pendingDelete = preset.id to preset.name },
            )
          }
        }
      }
      FilledTonalButton(
        shapes = ButtonDefaults.shapes(),
        onClick = { onEvent(ToneEvent.Create) },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(Icons.Rounded.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(Res.string.action_add_tone))
      }
    }
  }

  state.editor?.let { editor -> ToneEditorDialog(editor = editor, onEvent = onEvent) }
}

/**
 * Tone detection (#32): the "Detect tone while typing" switch, with its warning, and the latest
 * reading — labels and the model's one-line summary.
 */
@Composable
private fun ToneDetectionCard(state: ToneUiState, onEvent: (ToneEvent) -> Unit) {
  SectionCard(
    title = stringResource(Res.string.tone_detection_title),
    icon = Icons.Rounded.Mood,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f).padding(end = 8.dp)) {
        Text(
          text = stringResource(Res.string.tone_detect_while_typing_title),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = stringResource(Res.string.tone_detect_while_typing_desc),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Switch(
        checked = state.detectWhileTyping,
        onCheckedChange = { onEvent(ToneEvent.DetectWhileTypingChanged(it)) },
      )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        Icons.Rounded.Thermostat,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.size(18.dp),
      )
      Spacer(Modifier.width(8.dp))
      Text(
        text = stringResource(Res.string.tone_detect_warning),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
      )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text(
      text = stringResource(Res.string.tone_last_reading),
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.SemiBold,
    )
    val reading = state.lastReading
    if (reading == null) {
      Text(
        text = stringResource(Res.string.tone_no_reading),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      ToneReadingView(reading.labels, reading.summary)
    }
    Text(
      text = stringResource(Res.string.tone_privacy_note),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun TonePresetRow(
  preset: TonePreset,
  isSelected: Boolean,
  onSelect: () -> Unit,
  onEdit: () -> Unit,
  onDelete: (() -> Unit)?,
) {
  val name = toneName(preset)
  Surface(
    shape = MaterialTheme.shapes.large,
    color =
      if (isSelected) MaterialTheme.colorScheme.primaryContainer
      else MaterialTheme.colorScheme.surfaceContainerHigh,
    modifier =
      Modifier.fillMaxWidth()
        .selectable(selected = isSelected, role = Role.RadioButton, onClick = onSelect),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
    ) {
      RadioButton(selected = isSelected, onClick = onSelect)
      Column(Modifier.weight(1f).padding(end = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
          )
          if (preset.hasEditedPrompt) {
            Spacer(Modifier.width(8.dp))
            StatusPill(stringResource(Res.string.tone_edited), StatusTone.NEUTRAL)
          }
        }
        Text(
          text = preset.systemPrompt,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      IconButton(shapes = IconButtonDefaults.shapes(), onClick = onEdit) {
        Icon(
          Icons.Rounded.Edit,
          contentDescription = stringResource(Res.string.action_edit_tone, name),
        )
      }
      if (onDelete != null) {
        IconButton(shapes = IconButtonDefaults.shapes(), onClick = onDelete) {
          Icon(
            Icons.Rounded.DeleteOutline,
            contentDescription = stringResource(Res.string.action_delete_tone, name),
          )
        }
      }
    }
  }
}

@Composable
private fun ToneEditorDialog(editor: ToneEditorState, onEvent: (ToneEvent) -> Unit) {
  AlertDialog(
    onDismissRequest = { onEvent(ToneEvent.Dismiss) },
    title = {
      Text(if (editor.presetId == null) stringResource(Res.string.tone_editor_new) else editor.name)
    },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        // A built-in tone keeps its name and its worked examples; only the prompt is the user's.
        if (!editor.isBuiltIn) {
          OutlinedTextField(
            value = editor.name,
            onValueChange = { onEvent(ToneEvent.NameChanged(it)) },
            label = { Text(stringResource(Res.string.tone_editor_name)) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
          )
        }
        OutlinedTextField(
          value = editor.systemPrompt,
          onValueChange = { onEvent(ToneEvent.PromptChanged(it)) },
          label = { Text(stringResource(Res.string.tone_editor_prompt)) },
          minLines = 5,
          shape = MaterialTheme.shapes.medium,
          modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
        )
        if (!editor.isBuiltIn) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text(
                text = stringResource(Res.string.tone_thinking_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
              )
              Text(
                text = stringResource(Res.string.tone_thinking_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
              checked = editor.usesThinking,
              onCheckedChange = { onEvent(ToneEvent.ThinkingChanged(it)) },
            )
          }
        }
        if (editor.canReset) {
          TextButton(
            shapes = ButtonDefaults.shapes(),
            onClick = { onEvent(ToneEvent.ResetPrompt) },
          ) {
            Text(stringResource(Res.string.action_reset_prompt))
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        shapes = ButtonDefaults.shapes(),
        onClick = { onEvent(ToneEvent.Save) },
        enabled = editor.isValid,
      ) {
        Text(stringResource(Res.string.action_save))
      }
    },
    dismissButton = {
      TextButton(shapes = ButtonDefaults.shapes(), onClick = { onEvent(ToneEvent.Dismiss) }) {
        Text(stringResource(Res.string.action_cancel))
      }
    },
  )
}

@Composable
private fun DeleteToneDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
    title = { Text(stringResource(Res.string.action_delete_tone, name)) },
    text = { Text(stringResource(Res.string.tone_delete_message)) },
    confirmButton = {
      TextButton(
        shapes = ButtonDefaults.shapes(),
        onClick = onConfirm,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
      ) {
        Text(stringResource(Res.string.action_delete))
      }
    },
    dismissButton = {
      TextButton(shapes = ButtonDefaults.shapes(), onClick = onDismiss) {
        Text(stringResource(Res.string.action_cancel))
      }
    },
  )
}
