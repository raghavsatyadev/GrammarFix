package com.raghavsatyadev.grammarfix

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.raghavsatyadev.grammarfix.engine.LlmRuntime
import com.raghavsatyadev.grammarfix.library.support.di.initKoin
import com.raghavsatyadev.grammarfix.service.IdleModelUnloader
import com.raghavsatyadev.grammarfix.service.MODEL_IDLE_TIMEOUT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger

class CoreApp : Application() {

  private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  /**
   * Frees the model when nothing can use it. The floating assistant reports itself; activities are
   * counted here.
   */
  val modelUnloader =
    IdleModelUnloader(appScope) {
      // Freeing ~1.4 GB of native memory stays off the main thread.
      val freed = withContext(Dispatchers.Default) { LlmRuntime.engine.releaseIfIdle() }
      if (freed) Log.i(TAG, "Unloaded the model after $MODEL_IDLE_TIMEOUT with no app or assistant")
    }

  override fun onCreate() {
    super.onCreate()
    initKoin {
      androidLogger()
      androidContext(this@CoreApp)
    }
    registerActivityLifecycleCallbacks(StartedActivities(modelUnloader::onAppVisible))
    appScope.launch { LlmRuntime.engine.activity.collect(modelUnloader::onInferenceActivity) }
  }

  /** Reports whether any of the app's activities is started, i.e. its UI is on screen. */
  private class StartedActivities(private val onVisible: (Boolean) -> Unit) :
    ActivityLifecycleCallbacks {
    private var started = 0

    override fun onActivityStarted(activity: Activity) {
      if (started++ == 0) onVisible(true)
    }

    override fun onActivityStopped(activity: Activity) {
      if (--started == 0) onVisible(false)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    override fun onActivityResumed(activity: Activity) {}

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {}
  }

  private companion object {
    const val TAG = "CoreApp"
  }
}
