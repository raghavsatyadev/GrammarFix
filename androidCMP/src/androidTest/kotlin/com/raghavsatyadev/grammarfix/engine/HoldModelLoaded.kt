package com.raghavsatyadev.grammarfix.engine

import androidx.test.platform.app.InstrumentationRegistry
import com.raghavsatyadev.grammarfix.CoreApp

/**
 * Runs [block] with the app's idle unload (#53) held off.
 *
 * An on-device eval drives the model through its own driver or engine, which the app's
 * [com.raghavsatyadev.grammarfix.service.IdleModelUnloader] does not watch. But the native model is
 * one per process, so a minute into a run with no activity started and no assistant, the unloader
 * freed it from under the eval ("Model is not loaded"). Reporting the assistant as running is the
 * unloader's own "something may use the model" signal; it is put back afterwards.
 */
inline fun <T> holdModelLoaded(block: () -> T): T {
  val app =
    InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as? CoreApp
  app?.modelUnloader?.onAssistantRunning(true)
  try {
    return block()
  } finally {
    app?.modelUnloader?.onAssistantRunning(false)
  }
}
