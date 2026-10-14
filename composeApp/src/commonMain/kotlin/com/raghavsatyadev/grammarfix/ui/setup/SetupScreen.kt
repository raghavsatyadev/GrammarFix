@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.raghavsatyadev.grammarfix.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.raghavsatyadev.grammarfix.ui.components.SectionCard
import com.raghavsatyadev.grammarfix.ui.components.StatusPill
import com.raghavsatyadev.grammarfix.ui.components.StatusTone
import grammarfix.composeapp.generated.resources.Res
import grammarfix.composeapp.generated.resources.action_grant
import grammarfix.composeapp.generated.resources.action_manage
import grammarfix.composeapp.generated.resources.action_not_now
import grammarfix.composeapp.generated.resources.action_open_model
import grammarfix.composeapp.generated.resources.action_start_assistant
import grammarfix.composeapp.generated.resources.action_stop_assistant
import grammarfix.composeapp.generated.resources.assistant_desc
import grammarfix.composeapp.generated.resources.assistant_running
import grammarfix.composeapp.generated.resources.assistant_stopped
import grammarfix.composeapp.generated.resources.assistant_title
import grammarfix.composeapp.generated.resources.download_status_ready
import grammarfix.composeapp.generated.resources.model_card_desc
import grammarfix.composeapp.generated.resources.model_card_title
import grammarfix.composeapp.generated.resources.model_required_body
import grammarfix.composeapp.generated.resources.model_required_title
import grammarfix.composeapp.generated.resources.perm_a11y_desc
import grammarfix.composeapp.generated.resources.perm_a11y_title
import grammarfix.composeapp.generated.resources.perm_granted
import grammarfix.composeapp.generated.resources.perm_needed
import grammarfix.composeapp.generated.resources.perm_overlay_desc
import grammarfix.composeapp.generated.resources.perm_overlay_title
import grammarfix.composeapp.generated.resources.setup_intro
import org.jetbrains.compose.resources.stringResource

/**
 * The floating assistant's permissions and on/off switch. Only Android has an assistant, so only
 * the Android host supplies this; other platforms leave the Setup tab out.
 */
@Immutable
data class AssistantSetup(
  val hasOverlayPermission: Boolean,
  val isAccessibilityEnabled: Boolean,
  val isAssistantRunning: Boolean,
  val onOpenOverlaySettings: () -> Unit,
  val onOpenAccessibilitySettings: () -> Unit,
  val onStartAssistant: () -> Unit,
  val onStopAssistant: () -> Unit,
)

/**
 * [isModelReady] says whether the offline model is fully downloaded. The assistant corrects text
 * with that model only, so it does not start without it: the user is sent to the Model tab through
 * [onOpenModelTab] instead.
 */
@Composable
fun SetupScreen(
  setup: AssistantSetup,
  isModelReady: Boolean,
  onOpenModelTab: () -> Unit,
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
) {
  var showModelRequired by remember { mutableStateOf(false) }
  if (showModelRequired) {
    ModelRequiredDialog(
      onOpenModelTab = {
        showModelRequired = false
        onOpenModelTab()
      },
      onDismiss = { showModelRequired = false },
    )
  }

  Column(
    modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      text = stringResource(Res.string.setup_intro),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )

    PermissionCard(
      title = stringResource(Res.string.model_card_title),
      description = stringResource(Res.string.model_card_desc),
      icon = Icons.Rounded.CloudDownload,
      granted = isModelReady,
      onClick = onOpenModelTab,
      actionLabel = stringResource(Res.string.action_open_model),
      grantedLabel = stringResource(Res.string.download_status_ready),
    )
    PermissionCard(
      title = stringResource(Res.string.perm_overlay_title),
      description = stringResource(Res.string.perm_overlay_desc),
      icon = Icons.Rounded.Layers,
      granted = setup.hasOverlayPermission,
      onClick = setup.onOpenOverlaySettings,
    )
    PermissionCard(
      title = stringResource(Res.string.perm_a11y_title),
      description = stringResource(Res.string.perm_a11y_desc),
      icon = Icons.Rounded.Accessibility,
      granted = setup.isAccessibilityEnabled,
      onClick = setup.onOpenAccessibilitySettings,
    )

    SectionCard(
      title = stringResource(Res.string.assistant_title),
      icon = Icons.Rounded.Widgets,
      trailing = {
        StatusPill(
          text =
            stringResource(
              if (setup.isAssistantRunning) Res.string.assistant_running
              else Res.string.assistant_stopped
            ),
          tone = if (setup.isAssistantRunning) StatusTone.POSITIVE else StatusTone.NEUTRAL,
        )
      },
    ) {
      Text(
        text = stringResource(Res.string.assistant_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (setup.isAssistantRunning) {
          OutlinedButton(shapes = ButtonDefaults.shapes(), onClick = setup.onStopAssistant) {
            Icon(Icons.Rounded.Stop, contentDescription = null)
            Text(stringResource(Res.string.action_stop_assistant), Modifier.padding(start = 8.dp))
          }
        } else {
          Button(
            shapes = ButtonDefaults.shapes(),
            onClick = {
              // Checked on every tap, not only when the screen opened: the model can be deleted
              // in the Model tab between the two.
              if (isModelReady) setup.onStartAssistant() else showModelRequired = true
            },
            enabled = setup.hasOverlayPermission && setup.isAccessibilityEnabled,
          ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Text(stringResource(Res.string.action_start_assistant), Modifier.padding(start = 8.dp))
          }
        }
      }
    }
  }
}

@Composable
private fun PermissionCard(
  title: String,
  description: String,
  icon: ImageVector,
  granted: Boolean,
  onClick: () -> Unit,
  actionLabel: String? = null,
  grantedLabel: String = stringResource(Res.string.perm_granted),
) {
  SectionCard(
    title = title,
    icon = icon,
    trailing = {
      StatusPill(
        text = if (granted) grantedLabel else stringResource(Res.string.perm_needed),
        tone = if (granted) StatusTone.POSITIVE else StatusTone.NEGATIVE,
      )
    },
  ) {
    Text(
      text = description,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
      if (granted) {
        FilledTonalButton(shapes = ButtonDefaults.shapes(), onClick = onClick) {
          Text(stringResource(Res.string.action_manage))
        }
      } else {
        Button(shapes = ButtonDefaults.shapes(), onClick = onClick) {
          Text(actionLabel ?: stringResource(Res.string.action_grant))
        }
      }
    }
  }
}

@Composable
private fun ModelRequiredDialog(onOpenModelTab: () -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
    title = { Text(stringResource(Res.string.model_required_title)) },
    text = { Text(stringResource(Res.string.model_required_body)) },
    confirmButton = {
      Button(shapes = ButtonDefaults.shapes(), onClick = onOpenModelTab) {
        Text(stringResource(Res.string.action_open_model))
      }
    },
    dismissButton = {
      TextButton(shapes = ButtonDefaults.shapes(), onClick = onDismiss) {
        Text(stringResource(Res.string.action_not_now))
      }
    },
  )
}
