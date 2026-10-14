package com.raghavsatyadev.grammarfix.service

import com.raghavsatyadev.grammarfix.engine.InferenceActivity
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How long the model stays loaded once nothing can use it. Long enough that closing the app to
 * check something and coming straight back does not pay a reload; short enough that ~1.4 GB of
 * native heap is not held for an assistant the user has put away.
 */
val MODEL_IDLE_TIMEOUT: Duration = 1.minutes

/**
 * Frees the model once the app and the floating assistant are both gone and it has been idle for
 * [idleTimeout].
 *
 * The accessibility service keeps the process alive indefinitely, so nothing else ever frees it:
 * closing the app and the bubble used to leave ~2.5 GB resident for good.
 *
 * The countdown starts from the later of "both gone" and "last inference finished", and anything
 * that could use the model cancels it: the app coming back, the assistant starting, or a correction
 * starting (the accessibility service still corrects text without the bubble, and that counts). A
 * later correction reloads the model on its own, so unloading between two of them is only a cost,
 * never a failure.
 *
 * All the deciding is here, driven by plain calls, so it can be tested with a virtual clock. The
 * app wires [onAppVisible], [onAssistantRunning] and [onInferenceActivity] to the real signals.
 *
 * [release] must itself refuse to free a model that is in use; this class only decides when to ask.
 */
class IdleModelUnloader(
  private val scope: CoroutineScope,
  private val idleTimeout: Duration = MODEL_IDLE_TIMEOUT,
  private val release: suspend () -> Unit,
) {

  private var appVisible = false
  private var assistantRunning = false
  private var inferring = false
  private var lastFinished = 0L
  private var countdown: Job? = null

  /** Whether the countdown to [release] is running. */
  val isCountingDown: Boolean
    @Synchronized get() = countdown?.isActive == true

  /** The app has a started activity ([visible]), or no longer has one. */
  @Synchronized
  fun onAppVisible(visible: Boolean) {
    appVisible = visible
    reconsider(restart = false)
  }

  /** The floating assistant (bubble or popup) started or stopped. */
  @Synchronized
  fun onAssistantRunning(running: Boolean) {
    assistantRunning = running
    reconsider(restart = false)
  }

  @Synchronized
  fun onInferenceActivity(activity: InferenceActivity) {
    // A correction that finished restarts the wait even if it began and ended between two reads.
    val finishedOne = activity.finished != lastFinished
    lastFinished = activity.finished
    inferring = activity.isRunning
    reconsider(restart = finishedOne)
  }

  private fun reconsider(restart: Boolean) {
    val idle = !appVisible && !assistantRunning && !inferring
    if (!idle) {
      countdown?.cancel()
      countdown = null
      return
    }
    // Already counting (or already released) for this idle stretch: a repeated "gone" signal must
    // not push the unload further away. Only a newly finished correction does.
    if (countdown != null && !restart) return
    countdown?.cancel()
    countdown = scope.launch {
      delay(idleTimeout)
      release()
    }
  }
}
