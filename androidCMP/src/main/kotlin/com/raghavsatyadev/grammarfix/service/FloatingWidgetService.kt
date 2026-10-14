package com.raghavsatyadev.grammarfix.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.raghavsatyadev.grammarfix.CoreApp
import com.raghavsatyadev.grammarfix.MainActivity
import com.raghavsatyadev.grammarfix.R
import com.raghavsatyadev.grammarfix.data.AppSettingsRepository
import com.raghavsatyadev.grammarfix.engine.LlmRuntime
import com.raghavsatyadev.grammarfix.engine.TextSuggestion
import com.raghavsatyadev.grammarfix.engine.ToneCheck
import com.raghavsatyadev.grammarfix.engine.ToneDetector
import com.raghavsatyadev.grammarfix.engine.TonePresetRepository
import com.raghavsatyadev.grammarfix.service.GrammarAccessibilityService.ApplyResult
import com.raghavsatyadev.grammarfix.ui.DismissTarget
import com.raghavsatyadev.grammarfix.ui.DockSpace
import com.raghavsatyadev.grammarfix.ui.LocalDockSpace
import com.raghavsatyadev.grammarfix.ui.OverlayRewriter
import com.raghavsatyadev.grammarfix.ui.RewriteResult
import com.raghavsatyadev.grammarfix.ui.SuggestionOverlay
import com.raghavsatyadev.grammarfix.ui.SuggestionOverlayActions
import com.raghavsatyadev.grammarfix.ui.SuggestionOverlayState
import com.raghavsatyadev.grammarfix.ui.isDocked
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/** What tapping the idle bubble does. */
internal enum class BubbleTapAction {
  REWRITE,
  OPEN_APP,
  /** The field is empty: there is nothing to open the overlay over, so the tap does nothing. */
  NOTHING,
}

/**
 * What a tap on the idle bubble does, given the focused field's text — null when no editable field
 * of another app is focused.
 *
 * With text to work on, the bubble opens the rewrite panel, as Grammarly's does when there is
 * nothing to fix. An empty field has nothing to rewrite, so the overlay stays collapsed. No field
 * means there is nowhere to write a rewrite back to, so the tap opens the app instead (#23).
 *
 * Pure, so the rule is tested without a window or an accessibility node.
 */
internal fun bubbleTapAction(fieldText: String?): BubbleTapAction =
  when {
    fieldText == null -> BubbleTapAction.OPEN_APP
    fieldText.isBlank() -> BubbleTapAction.NOTHING
    else -> BubbleTapAction.REWRITE
  }

/**
 * Draws the floating suggestion overlay ([SuggestionOverlay]) above other apps.
 *
 * A Compose view in a service window has no activity to take its lifecycle and saved state from, so
 * the service provides both.
 */
class FloatingWidgetService : Service(), LifecycleOwner, SavedStateRegistryOwner {

  private val lifecycleRegistry = LifecycleRegistry(this)
  override val lifecycle: Lifecycle
    get() = lifecycleRegistry

  private val savedStateController = SavedStateRegistryController.create(this)
  override val savedStateRegistry: SavedStateRegistry
    get() = savedStateController.savedStateRegistry

  private val state = MutableStateFlow<SuggestionOverlayState>(SuggestionOverlayState.Idle)

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  /** The rewrite panel (#31). Its rewrites go through the one engine the whole app shares. */
  private val rewriter by lazy { OverlayRewriter(LlmRuntime.engine, scope) }

  /** The tone read (#32), shared with the accessibility service that starts it while typing. */
  private val toneDetector: ToneDetector? by lazy {
    runCatching { KoinPlatform.getKoin().get<ToneDetector>() }.getOrNull()
  }
  private val settings: AppSettingsRepository? by lazy {
    runCatching { KoinPlatform.getKoin().get<AppSettingsRepository>() }.getOrNull()
  }

  /**
   * What the overlay showed when the rewrite panel opened, kept up to date by analyses that finish
   * while it is open, and shown again when the panel closes. The panel is a detour from the review,
   * so a new analysis must not yank the user out of it.
   */
  private var beneathRewrite: SuggestionOverlayState? = null

  private var windowManager: WindowManager? = null
  private var overlayView: View? = null
  private var params: WindowManager.LayoutParams? = null

  /**
   * Where the collapsed overlay was left. The sheet takes the window over while it is docked, so
   * the bubble's position has to be remembered rather than read back off the window.
   *
   * Set from [OverlayPlacement] in [onCreate], once there is a context to read the screen's density
   * from.
   */
  private var floatingX = 0
  private var floatingY = 0

  /** The room a docked sheet takes; see [DockSpace]. */
  private val dockSpace = MutableStateFlow(DockSpace())

  /** The drop-to-close target: its own window, alive only while the overlay is being dragged. */
  private val dismissArmed = MutableStateFlow(false)
  private var dismissView: ComposeView? = null
  private var drag: DragToDismiss? = null

  override fun onCreate() {
    savedStateController.performAttach()
    savedStateController.performRestore(null)
    super.onCreate()
    lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    instance = this
    // While the bubble or popup is up the model stays loaded, however long it sits unused.
    (application as? CoreApp)?.modelUnloader?.onAssistantRunning(true)
    val (restingX, restingY) = OverlayPlacement.restingPosition(resources.displayMetrics.density)
    floatingX = restingX
    floatingY = restingY
    startForegroundServiceWithNotification()
    initOverlayWindow()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun startForegroundServiceWithNotification() {
    val channelId = "floating_widget_channel"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(
          channelId,
          getString(R.string.channel_floating_widget_name),
          NotificationManager.IMPORTANCE_LOW,
        )
      getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    val notification: Notification =
      NotificationCompat.Builder(this, channelId)
        .setContentTitle(getString(R.string.notification_floating_widget_title))
        .setContentText(getString(R.string.notification_floating_widget_text))
        .setSmallIcon(android.R.drawable.ic_menu_edit)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    startForeground(NOTIFICATION_ID, notification)
  }

  private fun initOverlayWindow() {
    windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

    val actions =
      SuggestionOverlayActions(
        onAccept = { index -> handle(GrammarAccessibilityService.applySuggestion(index)) },
        onDismiss = { index -> GrammarAccessibilityService.dismissSuggestion(index) },
        onAcceptAll = { handle(GrammarAccessibilityService.applyAll()) },
        onOpenSheet = ::openSheet,
        onCloseSheet = ::closeSheet,
        onDragStart = ::startDrag,
        onDrag = ::moveBy,
        onDragEnd = { endDrag(cancelled = false) },
        onDragCancel = { endDrag(cancelled = true) },
        onBubbleTap = ::onBubbleTap,
        onOpenRewrite = ::openRewrite,
        onSelectTone = { id ->
          setFocusable(false)
          rewriter.select(id)
        },
        onSendInstruction = { instruction ->
          setFocusable(false)
          rewriter.instruct(instruction)
        },
        onInstructionFocus = { focused ->
          setFocusable(focused)
          if (!SHEET_COVERS_KEYBOARD) GrammarAccessibilityService.allowKeyboard(focused)
          redock()
        },
        onUseRewrite = ::useRewrite,
        onCloseRewrite = ::closeRewrite,
        onDetectTone = { text -> toneDetector?.detectNow(text) },
        onUndo = { handle(GrammarAccessibilityService.undo(), appliedMessage = null) },
      )
    val noTone = MutableStateFlow<ToneCheck?>(null)
    val autoOff = MutableStateFlow(false)

    // The panel's own state — loading, then the answer — replaces the overlay's while it is open.
    scope.launch {
      rewriter.state.collect { panel ->
        if (panel != null && state.value is SuggestionOverlayState.Rewrite) setState(panel)
      }
    }

    val view =
      ComposeView(this).apply {
        setViewTreeLifecycleOwner(this@FloatingWidgetService)
        setViewTreeSavedStateRegistryOwner(this@FloatingWidgetService)
        setContent {
          val current by state.collectAsState()
          val tone by (toneDetector?.check ?: noTone).collectAsState()
          val autoDetect by (settings?.detectToneWhileTyping ?: autoOff).collectAsState()
          val canUndo by GrammarAccessibilityService.canUndo.collectAsState()
          val space by dockSpace.collectAsState()
          CompositionLocalProvider(LocalDockSpace provides space) {
            SuggestionOverlay(
              current,
              actions,
              tone = tone,
              autoDetectTone = autoDetect,
              canUndo = canUndo,
            )
          }
        }
      }
    // A tap anywhere outside an open sheet closes it, as it does for any bottom sheet. The tap
    // still
    // reaches the app underneath: the window is not touch-modal. The frame sees the event before
    // Compose does, so Compose cannot swallow it.
    val root =
      object : FrameLayout(this) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
          if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            closeOnOutsideTouch()
            return true
          }
          return super.dispatchTouchEvent(event)
        }
      }
    // Compose looks its owners up from the window's root view, which is now this frame.
    root.setViewTreeLifecycleOwner(this)
    root.setViewTreeSavedStateRegistryOwner(this)
    root.addView(view)
    overlayView = root

    params =
      WindowManager.LayoutParams(
          WindowManager.LayoutParams.WRAP_CONTENT,
          WindowManager.LayoutParams.WRAP_CONTENT,
          overlayWindowType(),
          // Never take focus: the user keeps typing in the app underneath.
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            // Only matters while the rewrite panel's instruction field makes the window
            // focusable: touches outside the overlay still go to the app underneath.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            // Tells the overlay about a tap outside it, so an open sheet can close.
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
          PixelFormat.TRANSLUCENT,
        )
        .apply {
          gravity = Gravity.TOP or Gravity.START
          x = floatingX
          y = floatingY
          // The sheet docks itself above the keyboard; the system must not move it again.
          softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

    try {
      windowManager?.addView(root, params)
    } catch (e: Exception) {
      Log.e(TAG, "Error adding overlay window", e)
    }
  }

  /**
   * Brings the app to the front from the overlay. A service has no task of its own, so the intent
   * needs [Intent.FLAG_ACTIVITY_NEW_TASK]; [Intent.FLAG_ACTIVITY_SINGLE_TOP] reuses the activity
   * instead of stacking a second copy when the app is already open behind the bubble.
   */
  private fun openApp() {
    val intent =
      Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    try {
      startActivity(intent)
    } catch (e: Exception) {
      Log.e(TAG, "Error opening the app from the overlay", e)
    }
  }

  /**
   * The idle bubble was tapped: rewrite the field's text if there is any, as Grammarly does,
   * otherwise open the app (#23).
   */
  private fun onBubbleTap() {
    val text = GrammarAccessibilityService.beginRewrite()
    when (bubbleTapAction(text)) {
      BubbleTapAction.REWRITE -> openRewrite(text.orEmpty())
      BubbleTapAction.OPEN_APP -> openApp()
      BubbleTapAction.NOTHING -> Unit
    }
  }

  /**
   * The pill's count was tapped: opens the sheet with every fix — or only those in the text the
   * user has selected. An empty field has nothing to review, so the overlay stays collapsed.
   */
  private fun openSheet() {
    val review = state.value as? SuggestionOverlayState.Review ?: return
    if (GrammarAccessibilityService.fieldText().isNullOrBlank()) return
    val scope = GrammarAccessibilityService.beginReview()
    setState(review.copy(open = true, scope = scope))
  }

  /** Every docked state collapses to the same place; only Review has fixes to come back to. */
  private fun closeSheet() {
    setState((state.value as? SuggestionOverlayState.Review)?.copy(open = false, scope = null))
  }

  /**
   * The user tapped outside the overlay. An open sheet or rewrite panel closes all the way; the
   * collapsed pill and bubble ignore it, since the user is just using the app underneath.
   *
   * Not while the rewrite panel's instruction field has the keyboard: every key press lands outside
   * the overlay's window.
   */
  private fun closeOnOutsideTouch() {
    if (!state.value.isDocked) return
    val p = params ?: return
    if (p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0) return
    if (state.value is SuggestionOverlayState.Rewrite) closeRewrite()
    closeSheet()
  }

  /** The sheet header's rewrite button. */
  private fun openRewrite() {
    val text = GrammarAccessibilityService.beginRewrite()
    if (text.isNullOrBlank()) {
      Toast.makeText(this, getString(R.string.toast_no_field), Toast.LENGTH_SHORT).show()
      return
    }
    openRewrite(text)
  }

  /**
   * Opens the rewrite panel over [text], the focused field's whole text, starting in the tone the
   * user has selected in the Tones tab.
   */
  private fun openRewrite(text: String) {
    val tones =
      try {
        KoinPlatform.getKoin().get<TonePresetRepository>()
      } catch (e: Exception) {
        Log.e(TAG, "No tone repository for the rewrite panel", e)
        return
      }
    scope.launch {
      // The first read from the database lands quickly, but a panel opened before it would offer
      // only the shipped tones and preselect the wrong one.
      tones.awaitLoaded()
      if (state.value !is SuggestionOverlayState.Rewrite) beneathRewrite = state.value
      rewriter.open(text, tones.presets.value, tones.selected.value.id)
      rewriter.state.value?.let(::setState)
    }
  }

  /** Back to whatever the panel was opened from, with any analysis that finished meanwhile. */
  private fun closeRewrite() {
    setFocusable(false)
    rewriter.close()
    val beneath = beneathRewrite
    beneathRewrite = null
    setState(beneath)
  }

  /** Writes the rewrite into the field through the accessibility service, as Accept does. */
  private fun useRewrite() {
    val panel = state.value as? SuggestionOverlayState.Rewrite ?: return
    val ready = panel.result as? RewriteResult.Ready ?: return
    // Hand input focus back first: the re-check that follows a write reads the focused field, and
    // that must be the user's again rather than the instruction field.
    setFocusable(false)
    val result = GrammarAccessibilityService.applyRewrite(panel.original, ready.text)
    if (result != ApplyResult.NO_FIELD) {
      rewriter.close()
      beneathRewrite = null
    }
    handle(result, getString(R.string.toast_rewrite_applied))
    // The rewritten text is checked again from scratch; the sheet waits for that in place.
    if (result == ApplyResult.APPLIED) setState(SuggestionOverlayState.Working)
  }

  /**
   * Lets the overlay's window take input focus, for as long as the rewrite panel's instruction
   * field wants the keyboard.
   *
   * The window is otherwise never focusable, so the user keeps typing in their own app. While it
   * is, it must stay non-modal — touches outside it still belong to the app underneath — and must
   * not be panned or resized by the keyboard, because it already positions itself above it.
   */
  private fun setFocusable(focusable: Boolean) {
    val p = params ?: return
    val flags =
      if (focusable) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
      else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    if (flags == p.flags) return
    p.flags = flags
    updateWindowLayout()
  }

  private fun isFocusable(): Boolean =
    params?.let { it.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0 } ?: false

  private fun overlayWindowType(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
      @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
    }

  /**
   * The user started dragging the bubble, so the way to close the assistant appears: a target at
   * the bottom of the screen, in its own full-width window because the overlay's window only wraps
   * the bubble. The target window is never touchable — the drag in progress belongs to the bubble.
   */
  private fun startDrag() {
    val wm = windowManager ?: return
    val p = params ?: return
    val bounds = screenSize(wm)
    drag =
      DragToDismiss.forScreen(bounds.first, bounds.second, resources.displayMetrics.density).apply {
        start(p.x, p.y)
      }
    dismissArmed.value = false

    if (dismissView != null) return
    val view =
      ComposeView(this).apply {
        setViewTreeLifecycleOwner(this@FloatingWidgetService)
        setViewTreeSavedStateRegistryOwner(this@FloatingWidgetService)
        setContent {
          val armed by dismissArmed.collectAsState()
          DismissTarget(armed)
        }
      }
    val targetParams =
      WindowManager.LayoutParams(
          WindowManager.LayoutParams.MATCH_PARENT,
          WindowManager.LayoutParams.WRAP_CONTENT,
          overlayWindowType(),
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
          PixelFormat.TRANSLUCENT,
        )
        .apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
    try {
      wm.addView(view, targetParams)
      dismissView = view
    } catch (e: Exception) {
      Log.e(TAG, "Error adding the close target window", e)
    }
  }

  private fun moveBy(delta: Offset) {
    val p = params ?: return
    val session = drag
    if (session == null) {
      p.x += delta.x.toInt()
      p.y += delta.y.toInt()
    } else {
      val view = overlayView
      val at = session.move(delta.x.toInt(), delta.y.toInt(), view?.width ?: 0, view?.height ?: 0)
      p.x = at.x
      p.y = at.y
      dismissArmed.value = session.isArmed
    }
    floatingX = p.x
    floatingY = p.y
    updateWindowLayout()
  }

  /** Dropping the bubble on the close target closes the assistant for good. */
  private fun endDrag(cancelled: Boolean) {
    val dismiss = drag?.end(cancelled) == true
    drag = null
    dismissArmed.value = false
    removeDismissTarget()
    if (!dismiss) return
    // Let the touch that released the bubble finish dispatching before its window is torn down.
    val view = overlayView
    if (view != null) view.post { stopSelf() } else stopSelf()
  }

  private fun removeDismissTarget() {
    val view = dismissView ?: return
    dismissView = null
    try {
      windowManager?.removeView(view)
    } catch (e: Exception) {
      Log.e(TAG, "Error removing the close target window", e)
    }
  }

  private fun screenSize(wm: WindowManager): Pair<Int, Int> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val bounds = wm.currentWindowMetrics.bounds
      bounds.width() to bounds.height()
    } else {
      val metrics = DisplayMetrics()
      @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
      metrics.widthPixels to metrics.heightPixels
    }

  /**
   * Tells the user what came of a change. A fix that landed needs no toast: the accessibility
   * service has already shown the fixes left, shifted to where they now sit, and Undo is on screen.
   * Only a failure, or [appliedMessage] when given, is said out loud.
   */
  private fun handle(result: ApplyResult, appliedMessage: String? = null) {
    val message =
      when (result) {
        ApplyResult.APPLIED -> appliedMessage
        ApplyResult.STALE -> getString(R.string.toast_text_stale)
        ApplyResult.NO_FIELD -> getString(R.string.toast_no_field)
      }
    if (message != null) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    // A stale fix starts a fresh check; the overlay shows that rather than fixes for old text.
    if (result == ApplyResult.STALE && state.value is SuggestionOverlayState.Review) {
      setState(
        if (state.value.isDocked) SuggestionOverlayState.Working
        else SuggestionOverlayState.Checking
      )
    }
  }

  private fun showSuggestions(
    text: String,
    suggestions: List<TextSuggestion>,
    bounds: Rect?,
    scope: IntRange?,
  ) {
    val shown = shownOrBeneath()
    val previous = shown as? SuggestionOverlayState.Review
    // Keep the sheet open if the user had opened it; a fresh analysis should not collapse it, and
    // neither should accepting a fix.
    val open = previous?.open ?: shown.isDocked
    // Dismissing the third of five fixes should leave the user looking at the next one, not send
    // them back to the top; a new analysis of different text starts over.
    val index = if (previous?.text == text) previous.index.coerceIn(0, suggestions.lastIndex) else 0
    val next = SuggestionOverlayState.Review(text, suggestions, index, open, scope.takeIf { open })
    if (!show(next)) return

    // Sit just above the field being edited. A docked sheet is already out of the way, and a field
    // with no room above it is left alone rather than shoved under the status bar.
    val pillTop =
      bounds
        ?.takeUnless { state.value.isDocked }
        ?.let {
          OverlayPlacement.pillTopFor(it.top, resources.displayMetrics.density)
        }
    if (pillTop != null) {
      floatingY = pillTop
      params?.y = floatingY
      updateWindowLayout()
    }
  }

  /** What the overlay shows, or would be showing if the rewrite panel were not open over it. */
  private fun shownOrBeneath(): SuggestionOverlayState =
    if (state.value is SuggestionOverlayState.Rewrite) beneathRewrite ?: SuggestionOverlayState.Idle
    else state.value

  /**
   * Shows [next], an update from the accessibility service — unless the rewrite panel is open, in
   * which case it waits beneath the panel for the user to come back. Returns whether it was shown.
   */
  private fun show(next: SuggestionOverlayState): Boolean {
    if (state.value is SuggestionOverlayState.Rewrite) {
      beneathRewrite = next
      return false
    }
    setState(next)
    return true
  }

  /**
   * Moves the overlay to the state's own state, and the window to the shape that state needs: the
   * sheet is full width and sits on the bottom edge above the keyboard, everything else is a small
   * window the user can drag wherever they like.
   *
   * A null [next] means "whatever the state was, collapse it", which is what the sheet's close
   * button does from a state that has nothing left to show.
   */
  private fun setState(next: SuggestionOverlayState?) {
    val target = next ?: SuggestionOverlayState.Idle
    val wasDocked = state.value.isDocked
    state.value = target
    // Where the sheet cannot cover the keyboard, it puts the keyboard away while it is open and
    // gives it back — only if it was up — when it collapses.
    if (!SHEET_COVERS_KEYBOARD && !wasDocked && target.isDocked) {
      GrammarAccessibilityService.hideKeyboard()
    }
    if (wasDocked && !target.isDocked) {
      GrammarAccessibilityService.endReview()
      if (!SHEET_COVERS_KEYBOARD) GrammarAccessibilityService.restoreKeyboard()
    }
    val p = params ?: return
    // A docked sheet reaches the screen's bottom edge, as the keyboard does, and keeps its own
    // content clear of the navigation bar (see [DockSpace]). The bubble stays inside the bars.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      p.fitInsetsTypes = if (target.isDocked) 0 else WindowInsets.Type.systemBars()
    }
    if (target.isDocked) {
      p.width = WindowManager.LayoutParams.MATCH_PARENT
      p.gravity = Gravity.BOTTOM or Gravity.START
      p.x = 0
      p.y = dockOffset()
      dockSpace.value = dockSpaceFor(p.y)
    } else {
      p.width = WindowManager.LayoutParams.WRAP_CONTENT
      p.gravity = Gravity.TOP or Gravity.START
      p.x = floatingX
      p.y = floatingY
    }
    updateWindowLayout()
  }

  /**
   * How far up from the bottom of the screen a docked sheet has to start.
   *
   * The sheet stands in for the keyboard, as Grammarly's does: it starts at the screen edge and
   * takes the keyboard's exact room, so nothing on the screen above it moves. From Android 12 an
   * overlay window draws over the keyboard, so the keyboard stays up under the sheet and is simply
   * there again when the sheet closes. Before that the sheet puts the keyboard away instead.
   *
   * Only while the rewrite panel's instruction field has focus is the keyboard for the sheet
   * itself; then the sheet sits on top of it. With no keyboard ever seen the sheet sits on the
   * navigation bar.
   */
  private fun dockOffset(): Int {
    val ownKeyboard =
      if (SHEET_COVERS_KEYBOARD) isFocusable() else !GrammarAccessibilityService.keyboardHeld()
    val keyboard = if (ownKeyboard) GrammarAccessibilityService.keyboardHeight() else 0
    return when {
      keyboard > 0 -> keyboard
      GrammarAccessibilityService.lastKeyboardHeight() > 0 -> 0
      else -> navigationBarHeight()
    }
  }

  /**
   * The keyboard's room for a sheet docked [offset] pixels up, or none if it is not on the edge.
   */
  private fun dockSpaceFor(offset: Int): DockSpace {
    val keyboard =
      GrammarAccessibilityService.keyboardHeight().takeIf { it > 0 }
        ?: GrammarAccessibilityService.lastKeyboardHeight()
    if (offset != 0 || keyboard == 0) return DockSpace()
    val density = resources.displayMetrics.density
    return DockSpace(
      minHeight = (keyboard / density).dp,
      bottomInset = (navigationBarHeight() / density).dp,
    )
  }

  private fun navigationBarHeight(): Int {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
    val insets = windowManager?.currentWindowMetrics?.windowInsets ?: return 0
    return insets.getInsets(WindowInsets.Type.navigationBars()).bottom
  }

  /** Moves a docked sheet back onto the keyboard's edge after that edge has moved. */
  private fun redock() {
    val p = params ?: return
    if (!state.value.isDocked) return
    val y = dockOffset()
    dockSpace.value = dockSpaceFor(y)
    if (p.y == y) return
    p.y = y
    updateWindowLayout()
  }

  private fun updateWindowLayout() {
    if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
    try {
      windowManager?.updateViewLayout(overlayView, params)
    } catch (e: Exception) {
      Log.e(TAG, "Error updating window layout", e)
    }
  }

  override fun onDestroy() {
    if (!SHEET_COVERS_KEYBOARD && state.value.isDocked) {
      GrammarAccessibilityService.restoreKeyboard()
    }
    scope.cancel()
    // Nobody is left to show a reading to, so stop any in flight.
    toneDetector?.clear()
    // Before the lifecycle owner dies: both windows are Compose views owned by this service.
    removeDismissTarget()
    drag = null
    lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    overlayView?.let { view ->
      try {
        windowManager?.removeView(view)
      } catch (e: Exception) {
        Log.e(TAG, "Error removing overlay view", e)
      }
    }
    overlayView = null
    if (instance == this) instance = null
    (application as? CoreApp)?.modelUnloader?.onAssistantRunning(false)
    super.onDestroy()
  }

  companion object {
    private const val TAG = "FloatingWidgetService"

    /**
     * Whether an overlay window draws over the keyboard. From Android 12 the keyboard's window sits
     * with the app it types into, under every overlay; before that it sits above them.
     */
    private val SHEET_COVERS_KEYBOARD = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    private const val NOTIFICATION_ID = 1001

    @Volatile
    var instance: FloatingWidgetService? = null
      private set

    fun isRunning(): Boolean = instance != null

    fun onSuggestions(
      text: String,
      suggestions: List<TextSuggestion>,
      bounds: Rect?,
      scope: IntRange? = null,
    ) {
      if (suggestions.isEmpty()) return onWordCleared(text)
      instance?.let { service ->
        service.overlayView?.post { service.showSuggestions(text, suggestions, bounds, scope) }
      }
    }

    /**
     * The user is typing. Fixes for the text before this keystroke no longer line up with the
     * field, so the overlay goes back to its bubble until typing stops and [onChecking] follows.
     */
    fun onTyping() {
      val service = instance ?: return
      service.overlayView?.post {
        if (service.shownOrBeneath().isDocked) return@post
        service.show(SuggestionOverlayState.Idle)
      }
    }

    /**
     * Typing has stopped and the model is checking [text]. The collapsed overlay turns into a small
     * loading indicator; an open sheet keeps its place. An empty field has nothing to check.
     */
    fun onChecking(text: String) {
      val service = instance ?: return
      service.overlayView?.post {
        if (service.shownOrBeneath().isDocked) return@post
        service.show(
          if (text.isBlank()) SuggestionOverlayState.Idle else SuggestionOverlayState.Checking
        )
      }
    }

    /** Tells the user why nothing is being suggested instead of going silent. */
    fun onError(message: String) {
      instance?.let { service ->
        service.overlayView?.post { service.show(SuggestionOverlayState.Error(message)) }
      }
    }

    /**
     * The keyboard came or went. Only a docked sheet cares: it is positioned against the top of the
     * keyboard, so that edge moving leaves it floating over a gap or hidden behind the keys.
     */
    fun onKeyboardMoved() {
      val service = instance ?: return
      if (!service.state.value.isDocked) return
      service.overlayView?.post { service.redock() }
    }

    /**
     * GrammarFix's own screens came to the front, or left it. The overlay hides over them: there is
     * no field there it would check, and the bubble covered the Insights tiles.
     */
    fun onOwnAppInFront(inFront: Boolean) {
      val service = instance ?: return
      service.overlayView?.post {
        service.overlayView?.visibility = if (inFront) View.GONE else View.VISIBLE
      }
    }

    fun onWordCleared(text: String = "") {
      instance?.let { service ->
        service.overlayView?.post {
          // An open sheet says the text is clean; it should not disappear mid-review. An emptied
          // field has nothing left to review, so the sheet collapses.
          service.show(
            if (service.shownOrBeneath().isDocked && text.isNotBlank())
              SuggestionOverlayState.AllClear
            else SuggestionOverlayState.Idle
          )
        }
      }
    }
  }
}
