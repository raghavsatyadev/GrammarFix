package com.raghavsatyadev.grammarfix.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.raghavsatyadev.grammarfix.data.AppSettingsRepository
import com.raghavsatyadev.grammarfix.engine.AcceptedFixRecorder
import com.raghavsatyadev.grammarfix.engine.GrammarEngine
import com.raghavsatyadev.grammarfix.engine.SuggestionKey
import com.raghavsatyadev.grammarfix.engine.TextAnalysis
import com.raghavsatyadev.grammarfix.engine.TextSuggestion
import com.raghavsatyadev.grammarfix.engine.ToneDetector
import com.raghavsatyadev.grammarfix.engine.afterApplying
import com.raghavsatyadev.grammarfix.engine.isWithin
import com.raghavsatyadev.grammarfix.engine.key
import com.raghavsatyadev.grammarfix.engine.shiftedBy
import com.raghavsatyadev.grammarfix.engine.withoutDismissed
import com.raghavsatyadev.grammarfix.engine.wordScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/**
 * Whether [text] is worth running the model over, given the analysis we are already showing
 * ([analysed]) and the one already on its way ([pending], running when [isPendingActive]).
 *
 * Most accessibility events are not an edit. A cursor key, a tap that only moves the caret, the
 * field regaining focus, the keyboard closing — each of those arrives as a text-selection, focus or
 * window event carrying text the model has already answered for. Running it again costs the user
 * several seconds of inference to reproduce the suggestions on screen, and wrote one more identical
 * row into their statistics every time, which is what made "corrections made" meaningless (#34).
 *
 * A run already in flight for the same text is left to finish rather than cancelled and restarted,
 * so a burst of events during typing cannot keep pushing the answer further away.
 *
 * Pure, and separate from the service, so the rule can be tested without an accessibility node.
 */
internal fun needsAnalysis(
  text: String,
  analysed: String?,
  pending: String?,
  isPendingActive: Boolean,
): Boolean = text != analysed && !(isPendingActive && text == pending)

/**
 * Whether a field belongs to GrammarFix itself.
 *
 * The service is handed every editable field on the device, including the ones in our own app. Our
 * own screens already run their own inference when the user asks for it, so analysing them here
 * corrects the same sentence a second time: two concurrent runs of a 15-second model, and the
 * second one recorded as an overlay correction because by the time it reaches the recorder that is
 * genuinely what it is. It is the one way Playground text reaches the user's statistics despite
 * being excluded twice over (#33).
 *
 * Pure, like [needsAnalysis], so the rule can be tested without an accessibility node.
 */
internal fun isOurOwnField(fieldPackage: CharSequence?, ownPackage: String): Boolean =
  fieldPackage?.contentEquals(ownPackage) == true

/**
 * Whether the user has left the field the overlay was showing fixes for.
 *
 * Only a focus or window change can mean that: a text or selection event always comes from a field.
 * With no editable field focused — the note was closed, the user went to another screen or app —
 * the fixes describe text that is no longer in front of them, so they go. GrammarFix's own windows
 * (the review sheet, the rewrite panel) taking focus is the user working with those fixes, not
 * leaving them.
 */
internal fun leftTheField(
  eventType: Int,
  hasFocusedField: Boolean,
  activeWindowIsOurs: Boolean,
): Boolean =
  (eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
    eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) &&
    !hasFocusedField &&
    !activeWindowIsOurs

class GrammarAccessibilityService : AccessibilityService() {

  enum class ApplyResult {
    APPLIED,
    /** The field changed since it was analysed; a fresh analysis has been started. */
    STALE,
    NO_FIELD,
  }

  private val serviceScope = CoroutineScope(Dispatchers.Main)
  private var analysisJob: Job? = null

  /** The last analysis whose text still matched the field when it finished. */
  private var currentAnalysis: TextAnalysis? = null

  /** Fixes the user dismissed; kept out of later analyses until the field is cleared. */
  private val dismissed = mutableSetOf<SuggestionKey>()

  /**
   * The text [analysisJob] is running for, so an event repeating it does not start a second run.
   */
  private var pendingText: String? = null

  /**
   * The field the rewrite panel was opened over (#31).
   *
   * Pinned rather than looked up again on `Use this version`: while the user types an instruction
   * the overlay holds input focus, so the focused field is our own, not the one being rewritten.
   */
  private var rewriteTarget: AccessibilityNodeInfo? = null

  /**
   * The part of the field the rewrite panel works on: the user's selection when the panel opened,
   * or null for the whole text. Only this range is replaced; the rest of the field is left alone.
   */
  private var rewriteRange: IntRange? = null

  /**
   * The whole field when the rewrite panel opened, so a selection is replaced only in that text.
   */
  private var rewriteField: String? = null

  /**
   * The text the user selected when the review sheet opened, widened to whole words. The sheet
   * offers only the fixes inside it, and Accept all touches nothing outside it.
   */
  private var reviewScope: IntRange? = null

  /** The field before each change the overlay made, newest last, for Undo. */
  private val undoStack = ArrayDeque<UndoEntry>()

  /** Whether the keyboard was up when the sheet hid it, so closing the sheet brings it back. */
  private var keyboardWasUp = false
  private var keyboardHidden = false
  private var keyboardAllowed = false

  /**
   * The keyboard's height the last time it was up, in pixels. The docked sheet takes this exact
   * room when it hides the keyboard, so it looks like the keyboard turned into the sheet.
   */
  private var lastKeyboardHeight = 0

  /**
   * Reads the tone once typing settles and the grammar check is done (#32), when the user has
   * turned that on. Null only if the app's graph is not up, in which case there is simply no tone.
   */
  private val toneDetector: ToneDetector? by lazy {
    runCatching { KoinPlatform.getKoin().get<ToneDetector>() }.getOrNull()
  }
  private val settings: AppSettingsRepository? by lazy {
    runCatching { KoinPlatform.getKoin().get<AppSettingsRepository>() }.getOrNull()
  }
  /** Counts the fixes the user accepts, for Insights. Null only if the app's graph is not up. */
  private val acceptedFixes: AcceptedFixRecorder? by lazy {
    runCatching { KoinPlatform.getKoin().get<AcceptedFixRecorder>() }.getOrNull()
  }

  override fun onServiceConnected() {
    super.onServiceConnected()
    instance = this
    Log.d(TAG, "GrammarAccessibilityService connected")
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event == null) return

    if (
      event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
        event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
    ) {
      frontAppPackage()?.let {
        FloatingWidgetService.onOwnAppInFront(isOurOwnField(it, packageName))
      }
    }

    // The keyboard opening or closing moves the edge the overlay's sheet is docked against, so the
    // sheet has to follow it. This never schedules an analysis: window changes fire constantly, and
    // scheduling would cancel whatever inference is in flight every time one arrived.
    if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
      imeHeight().takeIf { it > 0 }?.let { lastKeyboardHeight = it }
      FloatingWidgetService.onKeyboardMoved()
      return
    }

    val eventType = event.eventType
    if (
      eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
        eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
        eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ||
        eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
    ) {
      val focusedNode = focusedEditable() ?: event.source?.takeIf { it.isEditable }
      if (focusedNode == null) {
        val activeWindowIsOurs = isOurOwnField(rootInActiveWindow?.packageName, packageName)
        if (leftTheField(eventType, hasFocusedField = false, activeWindowIsOurs)) dropSuggestions()
        return
      }
      if (isOurOwnField(focusedNode.packageName, packageName)) return
      scheduleAnalysis(focusedNode)
    }
  }

  /**
   * The package of the app the user is looking at: the focused application window's. The overlay's
   * own windows are not application windows, so they never count as GrammarFix being in front.
   */
  private fun frontAppPackage(): CharSequence? =
    windows
      .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
      ?.root
      ?.packageName

  /** Forgets the field the user left and puts the overlay back to its idle bubble. */
  private fun dropSuggestions() {
    analysisJob?.cancel()
    pendingText = null
    currentAnalysis = null
    reviewScope = null
    clearUndo()
    toneDetector?.onTextChanged("")
    FloatingWidgetService.onWordCleared()
  }

  private fun focusedEditable(): AccessibilityNodeInfo? =
    rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

  /** An empty field can report its hint as text; treat that as empty. */
  private fun AccessibilityNodeInfo.realText(): String =
    if (isShowingHintText) "" else text?.toString().orEmpty()

  private fun scheduleAnalysis(node: AccessibilityNodeInfo, delayMs: Long = DEBOUNCE_MS) {
    val text = node.realText()
    val bounds = Rect().also { node.getBoundsInScreen(it) }

    val running = analysisJob?.isActive == true
    if (!needsAnalysis(text, currentAnalysis?.analyzedText, pendingText, running)) return

    // The text changed by hand, so the overlay's own changes can no longer be undone over it and
    // the selection the sheet was scoped to may no longer exist.
    if (undoStack.lastOrNull()?.after != text) clearUndo()
    reviewScope = null
    FloatingWidgetService.onChecking(text)
    // An edit: any tone read of the old text stops here, model and all.
    toneDetector?.onTextChanged(text)
    pendingText = text
    analysisJob?.cancel()
    analysisJob = serviceScope.launch {
      delay(delayMs)
      if (text.isBlank()) dismissed.clear()

      // Inference takes seconds. If the user typed, cleared the field or applied a fix in the
      // meantime, the result describes text that no longer exists; showing it would offer a fix
      // that cannot be applied. The engine asks this the moment the model returns and records the
      // verdict with the run, so what analytics counts and what the user is shown cannot disagree.
      val analysis =
        GrammarEngine.defaultEngine
          .analyze(text) { stillHolds(node, text) }
          .withoutDismissed(dismissed, GrammarEngine.defaultEngine)

      if (analysis.isStale) {
        Log.d(TAG, "Dropping stale analysis for '$text'")
        return@launch
      }

      currentAnalysis = analysis
      val error = analysis.errorMessage
      if (error != null) {
        Log.w(TAG, "Inference failed: $error")
        FloatingWidgetService.onError(error)
      } else {
        Log.d(TAG, "${analysis.suggestions.size} suggestion(s) for '$text'")
        FloatingWidgetService.onSuggestions(text, analysis.suggestions, bounds, reviewScope)
        // The grammar check is done; the tone read may follow once typing has settled.
        toneDetector?.onGrammarChecked(text, settings?.detectToneWhileTyping?.value == true)
      }
    }
  }

  /** Whether the field still holds the [text] an analysis was started for. */
  private fun stillHolds(node: AccessibilityNodeInfo, text: String): Boolean {
    val live = (focusedEditable() ?: node).apply { refresh() }.realText()
    if (live != text) Log.d(TAG, "Field moved on: '$text' is now '$live'")
    return live == text
  }

  /** Hides one suggestion, by its index in the last analysis, without touching the text. */
  fun dismissSuggestion(index: Int) {
    val analysis = currentAnalysis ?: return
    val suggestion = analysis.suggestions.getOrNull(index) ?: return
    dismissed += suggestion.key
    val remaining = analysis.withoutDismissed(dismissed, GrammarEngine.defaultEngine)
    currentAnalysis = remaining
    FloatingWidgetService.onSuggestions(
      remaining.analyzedText,
      remaining.suggestions,
      bounds = null,
      scope = reviewScope,
    )
  }

  /**
   * The sheet is opening: scopes it to the text the user has selected, if any, and returns that
   * scope. With nothing selected the sheet covers the whole field.
   */
  fun beginReview(): IntRange? {
    val node = focusedEditable()
    val text = node?.realText().orEmpty()
    reviewScope =
      node
        ?.let { wordScope(text, it.textSelectionStart, it.textSelectionEnd) }
        ?.takeIf { text == currentAnalysis?.analyzedText }
    return reviewScope
  }

  fun endReview() {
    reviewScope = null
  }

  /** The focused field's text, or null when no editable field of another app is focused. */
  fun fieldText(): String? =
    focusedEditable()?.takeUnless { isOurOwnField(it.packageName, packageName) }?.realText()

  /**
   * Hides the keyboard while the sheet is open, remembering whether it was up so [restoreKeyboard]
   * puts it back only if it was.
   */
  fun hideKeyboard() {
    if (keyboardHidden) return
    val height = imeHeight()
    keyboardWasUp = height > 0
    if (height > 0) lastKeyboardHeight = height
    keyboardHidden = true
    keyboardAllowed = false
    softKeyboardController.setShowMode(SHOW_MODE_HIDDEN)
  }

  /**
   * The sheet closed: lets the keyboard back, and reopens it over the field if it was up before.
   * Lifting the hidden mode alone does not always bring it back, so the field is tapped for it.
   */
  fun restoreKeyboard() {
    if (!keyboardHidden) return
    keyboardHidden = false
    softKeyboardController.setShowMode(SHOW_MODE_AUTO)
    if (!keyboardWasUp) return
    serviceScope.launch {
      delay(KEYBOARD_RESTORE_DELAY_MS)
      if (!keyboardHidden && imeHeight() == 0) {
        focusedEditable()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
      }
    }
  }

  /**
   * Lets the keyboard show while the rewrite panel's instruction field has focus, and hides it
   * again after. Only matters while the sheet is keeping it hidden.
   */
  fun allowKeyboard(allowed: Boolean) {
    if (!keyboardHidden) return
    keyboardAllowed = allowed
    softKeyboardController.setShowMode(if (allowed) SHOW_MODE_AUTO else SHOW_MODE_HIDDEN)
  }

  private fun clearUndo() {
    undoStack.clear()
    _canUndo.value = false
  }

  /** Puts the field back the way it was before the overlay's last change. */
  fun undo(): ApplyResult {
    val entry = undoStack.lastOrNull() ?: return ApplyResult.STALE
    val node = focusedEditable() ?: return ApplyResult.NO_FIELD
    if (node.realText() != entry.after) {
      clearUndo()
      return ApplyResult.STALE
    }
    undoStack.removeLast()
    _canUndo.value = undoStack.isNotEmpty()
    analysisJob?.cancel()
    pendingText = null
    if (!writeText(node, entry.before, entry.scope)) return ApplyResult.NO_FIELD
    acceptedFixes?.let { recorder ->
      serviceScope.launch { recorder.unrecord(entry.acceptedFixes) }
    }
    reviewScope = entry.scope
    publish(entry.analysis, entry.before)
    return ApplyResult.APPLIED
  }

  /**
   * Shows [analysis] — the fixes still left in [text] — without running the model. A missing one
   * means the text has not been checked yet, so it is.
   */
  private fun publish(analysis: TextAnalysis?, text: String) {
    currentAnalysis = analysis
    if (analysis == null) {
      focusedEditable()?.let { scheduleAnalysis(it, delayMs = 0L) }
      return
    }
    FloatingWidgetService.onSuggestions(text, analysis.suggestions, bounds = null, reviewScope)
  }

  /**
   * How tall the on-screen keyboard is right now, in pixels, or zero when none is showing.
   *
   * The overlay docks its sheet above the keyboard so it never covers the field being edited. The
   * accessibility service is the only part of the app that can see the keyboard's window, so the
   * measurement is taken here and handed over.
   */
  private fun imeHeight(): Int {
    val ime =
      windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return 0
    val bounds = Rect().also { ime.getBoundsInScreen(it) }
    return bounds.height().coerceAtLeast(0)
  }

  override fun onInterrupt() {
    Log.d(TAG, "GrammarAccessibilityService interrupted")
  }

  override fun onDestroy() {
    analysisJob?.cancel()
    _canUndo.value = false
    super.onDestroy()
    if (instance == this) {
      instance = null
    }
  }

  /** Applies one suggestion, by its index in the last analysis, leaving the rest of the text. */
  fun applySuggestion(index: Int): ApplyResult = applyFixes { analysis ->
    listOfNotNull(analysis.suggestions.getOrNull(index))
  }

  /**
   * Applies every fix left — only those inside the selection, when the sheet was opened over one —
   * in one action.
   */
  fun applyAll(): ApplyResult = applyFixes { analysis ->
    analysis.suggestions.filter { it.isWithin(reviewScope) }
  }

  /**
   * Writes [pick]'s fixes into the field and shows the fixes that are left straight away, shifted
   * to where they now sit. The model is not run again: nothing else in the text changed.
   */
  private fun applyFixes(pick: (TextAnalysis) -> List<TextSuggestion>): ApplyResult {
    val node = focusedEditable() ?: return ApplyResult.NO_FIELD
    val current = node.realText()
    val analysis = analysisOf(current)
    val fixes = analysis?.let(pick).orEmpty()
    val applied =
      analysis?.takeIf { fixes.isNotEmpty() }?.afterApplying(fixes, GrammarEngine.defaultEngine)
    if (applied == null) return applyToField(node) { null }

    analysisJob?.cancel()
    pendingText = null
    val newText = applied.analysis.analyzedText
    val scope = reviewScope
    val newScope = scope?.shiftedBy(applied.edits)
    if (!writeText(node, newText, selection = newScope)) return ApplyResult.NO_FIELD
    pushUndo(
      UndoEntry(
        before = current,
        analysis = analysis,
        scope = scope,
        after = newText,
        acceptedFixes = fixes.size,
      )
    )
    acceptedFixes?.let { recorder -> serviceScope.launch { recorder.record(fixes) } }
    reviewScope = newScope
    publish(applied.analysis, newText)
    return ApplyResult.APPLIED
  }

  private fun pushUndo(entry: UndoEntry) {
    undoStack.addLast(entry)
    if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
    _canUndo.value = true
  }

  /** The last analysis, if it was made of the text the field holds now. */
  private fun analysisOf(current: String): TextAnalysis? = currentAnalysis?.takeIf {
    it.analyzedText == current
  }

  /**
   * Pins the focused field for the rewrite panel and returns its text, or null when no field is
   * focused. `Use this version` writes back into this field, whatever has focus by then.
   */
  fun beginRewrite(): String? {
    val node = focusedEditable() ?: return null
    if (isOurOwnField(node.packageName, packageName)) return null
    val text = node.realText()
    rewriteTarget = node
    rewriteField = text
    rewriteRange =
      reviewScope?.takeIf { text == currentAnalysis?.analyzedText }
        ?: wordScope(text, node.textSelectionStart, node.textSelectionEnd)
    return rewriteRange?.let { text.substring(it) } ?: text
  }

  /**
   * Replaces the pinned field's text with [rewritten], through the same path Accept takes.
   *
   * Only if the field still holds [original], the text the rewrite was made from: anything typed
   * since would be silently thrown away otherwise.
   */
  fun applyRewrite(original: String, rewritten: String): ApplyResult {
    val target = rewriteTarget?.takeIf { it.refresh() }
    val range = rewriteRange
    val field = rewriteField
    rewriteTarget = null
    rewriteRange = null
    rewriteField = null
    return applyToField(target ?: focusedEditable(), selection = range) { current ->
      when {
        range == null -> rewritten.takeIf { current == original }
        // Only the selected part is replaced, and only in the text it was selected from.
        current != field || current.substring(range) != original -> null
        else -> current.replaceRange(range, rewritten)
      }
    }
  }

  private fun applyToField(
    node: AccessibilityNodeInfo? = focusedEditable(),
    selection: IntRange? = null,
    edit: (current: String) -> String?,
  ): ApplyResult {
    if (node == null) {
      Log.w(TAG, "Cannot apply: no editable field is focused")
      return ApplyResult.NO_FIELD
    }

    // A pending analysis of the old text must not land after we change it.
    analysisJob?.cancel()
    pendingText = null

    val current = node.realText()
    val newText = edit(current)
    if (newText == null) {
      Log.w(TAG, "Suggestion is stale for '$current'; re-analysing")
      currentAnalysis = null
      scheduleAnalysis(node, delayMs = 0L)
      return ApplyResult.STALE
    }

    // A rewrite of a selection keeps the rewritten part selected; its length may have changed.
    val newSelection = selection?.let {
      it.first until (it.last + 1 + newText.length - current.length)
    }
    if (!writeText(node, newText, newSelection)) return ApplyResult.NO_FIELD
    pushUndo(
      UndoEntry(before = current, analysis = analysisOf(current), scope = null, after = newText)
    )

    // A rewrite changes more than single words, so the text is checked again.
    currentAnalysis = null
    scheduleAnalysis(node)
    return ApplyResult.APPLIED
  }

  /**
   * Replaces the field's text with [newText], then selects [selection] — the text the user had
   * selected, so it stays selected — or puts the cursor at the end.
   */
  private fun writeText(
    node: AccessibilityNodeInfo,
    newText: String,
    selection: IntRange? = null,
  ): Boolean {
    val args =
      Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
      }
    val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    Log.d(TAG, "Text replacement success=$success")
    if (!success) return false

    val start = selection?.first?.coerceIn(0, newText.length) ?: newText.length
    val end = selection?.let { it.last + 1 }?.coerceIn(start, newText.length) ?: newText.length
    val cursor =
      Bundle().apply {
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
      }
    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursor)
    return true
  }

  /** The field before and after one change the overlay made, and the fixes it had before. */
  private data class UndoEntry(
    val before: String,
    val analysis: TextAnalysis?,
    val scope: IntRange?,
    val after: String,
    /** How many fixes this change accepted; a rewrite accepts none. */
    val acceptedFixes: Int = 0,
  )

  companion object {
    private const val TAG = "GrammarAccessService"
    private const val DEBOUNCE_MS = 300L
    private const val MAX_UNDO = 20
    private const val KEYBOARD_RESTORE_DELAY_MS = 250L

    private val _canUndo = MutableStateFlow(false)

    /** Whether the overlay's last change can be undone; drives its Undo button. */
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    @Volatile
    var instance: GrammarAccessibilityService? = null
      private set

    fun isServiceRunning(): Boolean = instance != null

    fun applySuggestion(index: Int): ApplyResult =
      instance?.applySuggestion(index) ?: ApplyResult.NO_FIELD

    fun applyAll(): ApplyResult = instance?.applyAll() ?: ApplyResult.NO_FIELD

    /** The focused field's text, pinned for the rewrite panel; null when there is none. */
    fun beginRewrite(): String? = instance?.beginRewrite()

    fun applyRewrite(original: String, rewritten: String): ApplyResult =
      instance?.applyRewrite(original, rewritten) ?: ApplyResult.NO_FIELD

    fun dismissSuggestion(index: Int) {
      instance?.dismissSuggestion(index)
    }

    fun undo(): ApplyResult = instance?.undo() ?: ApplyResult.NO_FIELD

    fun beginReview(): IntRange? = instance?.beginReview()

    fun endReview() {
      instance?.endReview()
    }

    fun fieldText(): String? = instance?.fieldText()

    fun hideKeyboard() {
      instance?.hideKeyboard()
    }

    fun restoreKeyboard() {
      instance?.restoreKeyboard()
    }

    fun allowKeyboard(allowed: Boolean) {
      instance?.allowKeyboard(allowed)
    }

    /** Zero when the service is not running, which leaves the sheet on the screen edge. */
    fun keyboardHeight(): Int = instance?.imeHeight() ?: 0

    /**
     * Whether the sheet is keeping the keyboard down right now. The keyboard's window can still be
     * on its way out, so its height is no guide in that moment.
     */
    fun keyboardHeld(): Boolean =
      instance?.let { it.keyboardHidden && !it.keyboardAllowed } ?: false

    /** The keyboard's height the last time it was up, or zero when none has been seen. */
    fun lastKeyboardHeight(): Int = instance?.lastKeyboardHeight ?: 0
  }
}
