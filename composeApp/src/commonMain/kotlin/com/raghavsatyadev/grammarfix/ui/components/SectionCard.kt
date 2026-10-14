@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.raghavsatyadev.grammarfix.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The rounded container every section of the app sits in. */
@Composable
fun SectionCard(
  title: String,
  icon: ImageVector,
  modifier: Modifier = Modifier,
  trailing: @Composable () -> Unit = {},
  content: @Composable ColumnScope.() -> Unit,
) {
  Surface(
    shape = MaterialTheme.shapes.extraLarge,
    color = MaterialTheme.colorScheme.surfaceContainer,
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
          modifier = Modifier.size(36.dp),
        ) {
          Icon(icon, contentDescription = null, modifier = Modifier.padding(8.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        trailing()
      }
      content()
    }
  }
}

/** How a [StatusPill] reads: fine, in progress, needs attention, or neutral. */
enum class StatusTone {
  POSITIVE,
  ACTIVE,
  NEGATIVE,
  NEUTRAL,
}

@Composable
fun StatusPill(text: String, tone: StatusTone, modifier: Modifier = Modifier) {
  val scheme = MaterialTheme.colorScheme
  val (container: Color, content: Color) =
    when (tone) {
      StatusTone.POSITIVE -> scheme.primaryContainer to scheme.onPrimaryContainer
      StatusTone.ACTIVE -> scheme.tertiaryContainer to scheme.onTertiaryContainer
      StatusTone.NEGATIVE -> scheme.errorContainer to scheme.onErrorContainer
      StatusTone.NEUTRAL -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
  Surface(shape = CircleShape, color = container, contentColor = content, modifier = modifier) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
    )
  }
}

/** One decimal place, without platform formatting APIs (not available in common code). */
fun Double.oneDecimal(): String = ((this * 10).toLong() / 10.0).toString()
