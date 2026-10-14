package com.raghavsatyadev.grammarfix.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarDiffAnalyzerTest {

  @Test
  fun computeDiff_identicalText_returnsUnchangedItem() {
    val text = "He goes to school."
    val diffs = GrammarDiffAnalyzer.computeDiff(text, text)

    assertEquals(1, diffs.size)
    assertEquals(DiffType.UNCHANGED, diffs[0].type)
    assertEquals(text, diffs[0].originalText)
  }

  @Test
  fun computeDiff_grammarCorrection_detectsReplacedWords() {
    val original = "he go to school"
    val corrected = "He goes to school"

    val diffs = GrammarDiffAnalyzer.computeDiff(original, corrected)

    assertTrue(diffs.any { it.type == DiffType.REPLACED })
    val replacement = diffs.first { it.type == DiffType.REPLACED }
    assertEquals("go", replacement.originalText)
    assertEquals("goes", replacement.correctedText)
  }

  @Test
  fun computeDiff_insertedWord_detectsInsertedType() {
    val original = "He school"
    val corrected = "He goes to school"

    val diffs = GrammarDiffAnalyzer.computeDiff(original, corrected)

    assertTrue(diffs.any { it.type == DiffType.INSERTED })
  }

  @Test
  fun computeDiff_deletedWord_detectsDeletedType() {
    val original = "He really goes to school"
    val corrected = "He goes to school"

    val diffs = GrammarDiffAnalyzer.computeDiff(original, corrected)

    assertTrue(diffs.any { it.type == DiffType.DELETED })
    val deleted = diffs.first { it.type == DiffType.DELETED }
    assertEquals("really", deleted.originalText)
  }
}
