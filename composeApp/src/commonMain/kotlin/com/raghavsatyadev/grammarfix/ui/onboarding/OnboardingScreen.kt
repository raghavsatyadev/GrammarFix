@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.raghavsatyadev.grammarfix.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import grammarfix.composeapp.generated.resources.Res
import grammarfix.composeapp.generated.resources.onboarding_assistant_body
import grammarfix.composeapp.generated.resources.onboarding_assistant_title
import grammarfix.composeapp.generated.resources.onboarding_done
import grammarfix.composeapp.generated.resources.onboarding_model_body
import grammarfix.composeapp.generated.resources.onboarding_model_title
import grammarfix.composeapp.generated.resources.onboarding_next
import grammarfix.composeapp.generated.resources.onboarding_private_body
import grammarfix.composeapp.generated.resources.onboarding_private_title
import grammarfix.composeapp.generated.resources.onboarding_skip
import grammarfix.composeapp.generated.resources.onboarding_welcome_body
import grammarfix.composeapp.generated.resources.onboarding_welcome_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private data class OnboardingPage(
  val icon: ImageVector,
  val title: StringResource,
  val body: StringResource,
)

private val WelcomePages =
  listOf(
    OnboardingPage(
      Icons.Rounded.Spellcheck,
      Res.string.onboarding_welcome_title,
      Res.string.onboarding_welcome_body,
    ),
    OnboardingPage(
      Icons.Rounded.Lock,
      Res.string.onboarding_private_title,
      Res.string.onboarding_private_body,
    ),
    OnboardingPage(
      Icons.Rounded.CloudDownload,
      Res.string.onboarding_model_title,
      Res.string.onboarding_model_body,
    ),
  )

private val AssistantPage =
  OnboardingPage(
    Icons.Rounded.Widgets,
    Res.string.onboarding_assistant_title,
    Res.string.onboarding_assistant_body,
  )

/**
 * What the app does and what it needs, shown once on first launch. [hasAssistantSetup] adds the
 * page about the floating assistant, which only Android has. Skip and finish both call [onDone].
 */
@Composable
fun OnboardingScreen(
  hasAssistantSetup: Boolean,
  onDone: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val pages = if (hasAssistantSetup) WelcomePages + AssistantPage else WelcomePages
  val pagerState = rememberPagerState { pages.size }
  val scope = rememberCoroutineScope()
  val isLast = pagerState.currentPage == pages.lastIndex

  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        if (!isLast) {
          TextButton(shapes = ButtonDefaults.shapes(), onClick = onDone) {
            Text(stringResource(Res.string.onboarding_skip))
          }
        }
      }

      HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
        PageContent(pages[index])
      }

      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        PageIndicator(count = pages.size, current = pagerState.currentPage)
        Button(
          shapes = ButtonDefaults.shapes(),
          onClick = {
            if (isLast) onDone()
            else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
          },
        ) {
          Text(
            stringResource(if (isLast) Res.string.onboarding_done else Res.string.onboarding_next)
          )
        }
      }
    }
  }
}

@Composable
private fun PageContent(page: OnboardingPage) {
  Column(
    Modifier.fillMaxSize().padding(horizontal = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Box(
      Modifier.size(144.dp)
        .clip(MaterialShapes.Cookie9Sided.toShape())
        .background(MaterialTheme.colorScheme.primaryContainer),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = page.icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(64.dp),
      )
    }
    Spacer(Modifier.height(40.dp))
    Text(
      text = stringResource(page.title),
      style = MaterialTheme.typography.headlineMedium,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))
    Text(
      text = stringResource(page.body),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun PageIndicator(count: Int, current: Int) {
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    repeat(count) { index ->
      val selected = index == current
      val width by
        animateDpAsState(
          targetValue = if (selected) 24.dp else 8.dp,
          animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        )
      val color by
        animateColorAsState(
          targetValue =
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
          animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        )
      Box(Modifier.width(width).height(8.dp).clip(CircleShape).background(color))
    }
  }
}
