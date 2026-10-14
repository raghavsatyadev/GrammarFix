package com.raghavsatyadev.grammarfix

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.raghavsatyadev.grammarfix.service.FloatingWidgetService
import com.raghavsatyadev.grammarfix.ui.GrammarFixApp
import com.raghavsatyadev.grammarfix.ui.setup.AssistantSetup
import com.raghavsatyadev.grammarfix.utils.PermissionUtils

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)

    setContent {
      val context = LocalContext.current
      var hasOverlayPermission by remember {
        mutableStateOf(PermissionUtils.hasOverlayPermission(context))
      }
      var isAccessibilityEnabled by remember {
        mutableStateOf(PermissionUtils.isAccessibilityServiceEnabled(context))
      }
      var isAssistantRunning by remember { mutableStateOf(FloatingWidgetService.isRunning()) }

      // Permissions are granted in system Settings, so re-read them whenever the user comes back.
      LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasOverlayPermission = PermissionUtils.hasOverlayPermission(context)
        isAccessibilityEnabled = PermissionUtils.isAccessibilityServiceEnabled(context)
        isAssistantRunning = FloatingWidgetService.isRunning()
      }

      GrammarFixApp(
        // The Playground is a developer tool, so it ships only in debug builds.
        showDeveloperTools = BuildConfig.DEBUG,
        assistantSetup =
          AssistantSetup(
            hasOverlayPermission = hasOverlayPermission,
            isAccessibilityEnabled = isAccessibilityEnabled,
            isAssistantRunning = isAssistantRunning,
            onOpenOverlaySettings = { PermissionUtils.openOverlayPermissionSettings(context) },
            onOpenAccessibilitySettings = { PermissionUtils.openAccessibilitySettings(context) },
            onStartAssistant = {
              startAssistant(context)
              isAssistantRunning = true
            },
            onStopAssistant = {
              context.stopService(Intent(context, FloatingWidgetService::class.java))
              isAssistantRunning = false
            },
          ),
      )
    }
  }

  private fun startAssistant(context: Context) {
    val intent = Intent(context, FloatingWidgetService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.startForegroundService(intent)
    } else {
      context.startService(intent)
    }
  }
}
