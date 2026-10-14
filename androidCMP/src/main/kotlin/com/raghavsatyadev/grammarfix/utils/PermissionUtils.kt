package com.raghavsatyadev.grammarfix.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import com.raghavsatyadev.grammarfix.service.GrammarAccessibilityService

object PermissionUtils {

  fun hasOverlayPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      Settings.canDrawOverlays(context)
    } else {
      true
    }
  }

  fun openOverlayPermissionSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      val intent =
        Intent(
          Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
          Uri.parse("package:${context.packageName}"),
        )
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
    }
  }

  fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedService =
      "${context.packageName}/${GrammarAccessibilityService::class.java.canonicalName}"
    val enabledServices =
      Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
      ) ?: return false

    val stringSplitter = TextUtils.SimpleStringSplitter(':')
    stringSplitter.setString(enabledServices)

    while (stringSplitter.hasNext()) {
      val service = stringSplitter.next()
      if (service.equals(expectedService, ignoreCase = true)) {
        return true
      }
    }
    return false
  }

  fun openAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
  }
}
