package com.raghavsatyadev.grammarfix.engine

/**
 * The analysis after [applied] was written into [TextAnalysis.analyzedText], computed without
 * running the model again.
 *
 * Accepting a fix changes only the words it covers, so every other fix the model found still holds:
 * it just sits a few characters further left or right. Those are shifted to where they now are, and
 * a fix that overlapped an applied one is dropped. The model runs again only when the user edits
 * the text by hand.
 *
 * Fixes are applied from the end of the text backwards, so each one leaves the offsets of the
 * earlier ones valid. Returns null when one of [applied] no longer matches the text.
 */
fun TextAnalysis.afterApplying(
  applied: List<TextSuggestion>,
  engine: GrammarEngine,
): AppliedFixes? {
  var text = analyzedText
  var remaining = suggestions.filterNot { it in applied }
  val edits = mutableListOf<TextEdit>()
  for (fix in applied.sortedByDescending { it.startIndex }) {
    val next = engine.applySuggestionAt(text, fix) ?: return null
    val edit = TextEdit(fix.startIndex, fix.endIndex, next.length - text.length)
    remaining = remaining.mapNotNull { it.shiftedBy(edit) }
    edits += edit
    text = next
  }
  val corrected =
    remaining
      .sortedByDescending { it.startIndex }
      .fold(text) { current, fix -> engine.applySuggestionAt(current, fix) ?: current }
  return AppliedFixes(TextAnalysis(text, corrected, remaining), edits)
}

/** The new analysis, and the edits that produced it, in the order they were made. */
data class AppliedFixes(val analysis: TextAnalysis, val edits: List<TextEdit>)

/** The characters [start] until [end] were replaced, changing the text's length by [delta]. */
data class TextEdit(val start: Int, val end: Int, val delta: Int)

/** Where this fix sits after [edit], or null when the edit overlapped it. */
private fun TextSuggestion.shiftedBy(edit: TextEdit): TextSuggestion? =
  when {
    endIndex <= edit.start && startIndex < edit.start -> this
    startIndex >= edit.end ->
      copy(startIndex = startIndex + edit.delta, endIndex = endIndex + edit.delta)
    else -> null
  }

/**
 * The range [start] until [end] — the text the user selected — after [edits]. An edit inside it
 * grows or shrinks it; one before it moves it.
 */
fun IntRange.shiftedBy(edits: List<TextEdit>): IntRange =
  edits.fold(this) { range, edit ->
    val start = range.first
    val end = range.last + 1
    when {
      edit.end <= start && edit.start < start -> (start + edit.delta) until (end + edit.delta)
      edit.start >= end -> range
      else -> start until (end + edit.delta)
    }
  }

/** Whether this fix lies wholly inside [scope], the text the user selected. */
fun TextSuggestion.isWithin(scope: IntRange?): Boolean =
  scope == null || (startIndex >= scope.first && endIndex <= scope.last + 1)

/**
 * [start] until [end] widened to whole words of [text], so a selection that starts or ends mid-word
 * still covers the fixes for the words it touches. Null for an empty selection.
 */
fun wordScope(text: String, start: Int, end: Int): IntRange? {
  val from = minOf(start, end).coerceIn(0, text.length)
  val to = maxOf(start, end).coerceIn(0, text.length)
  if (from == to) return null
  var a = from
  while (a > 0 && !text[a - 1].isWhitespace()) a--
  var b = to
  while (b < text.length && !text[b].isWhitespace()) b++
  return a until b
}
