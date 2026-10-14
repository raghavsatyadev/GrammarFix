package com.raghavsatyadev.grammarfix.engine

import com.raghavsatyadev.grammarfix.data.AcceptedFixDao
import com.raghavsatyadev.grammarfix.data.AcceptedFixEntity
import kotlin.time.Clock

/**
 * Writes one `accepted_fix` row per fix the overlay put into a field, and takes them back on Undo.
 *
 * Only the kind is kept: the suggestion's words are read for [kind] and dropped (#19).
 */
class AcceptedFixRecorder(
  private val dao: AcceptedFixDao,
  private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

  suspend fun record(fixes: List<TextSuggestion>) {
    if (fixes.isEmpty()) return
    val at = now()
    dao.insertAll(fixes.map { AcceptedFixEntity(at = at, kind = it.kind.diffType.name) })
  }

  /** Undo of an action that applied [count] fixes. */
  suspend fun unrecord(count: Int) {
    if (count > 0) dao.deleteLatest(count)
  }
}

private val SuggestionKind.diffType: DiffType
  get() =
    when (this) {
      SuggestionKind.ADD -> DiffType.INSERTED
      SuggestionKind.REMOVE -> DiffType.DELETED
      SuggestionKind.REPLACE -> DiffType.REPLACED
    }
