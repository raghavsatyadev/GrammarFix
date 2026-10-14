package com.raghavsatyadev.grammarfix.engine

enum class DiffType {
  UNCHANGED,
  INSERTED,
  DELETED,
  REPLACED,
}

data class DiffItem(
  val type: DiffType,
  val originalText: String,
  val correctedText: String,
  val startIndex: Int = -1,
  val endIndex: Int = -1,
)

object GrammarDiffAnalyzer {

  fun computeDiff(original: String, corrected: String): List<DiffItem> {
    if (original.trim() == corrected.trim()) {
      return if (original.isNotEmpty()) {
        listOf(DiffItem(DiffType.UNCHANGED, original, corrected))
      } else {
        emptyList()
      }
    }

    val origWords = original.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val corrWords = corrected.split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun clean(w: String) = w.lowercase().replace(Regex("^[^a-zA-Z0-9']+|[^a-zA-Z0-9']+$"), "")

    val m = origWords.size
    val n = corrWords.size
    val dp = Array(m + 1) { IntArray(n + 1) }

    for (i in 0..m) dp[i][0] = i
    for (j in 0..n) dp[0][j] = j

    for (i in 1..m) {
      for (j in 1..n) {
        if (clean(origWords[i - 1]) == clean(corrWords[j - 1])) {
          dp[i][j] = dp[i - 1][j - 1]
        } else {
          val replaceCost = dp[i - 1][j - 1] + 1
          val deleteCost = dp[i - 1][j] + 1
          val insertCost = dp[i][j - 1] + 1
          dp[i][j] = minOf(replaceCost, minOf(deleteCost, insertCost))
        }
      }
    }

    val result = mutableListOf<DiffItem>()
    var i = m
    var j = n

    while (i > 0 || j > 0) {
      if (i > 0 && j > 0 && clean(origWords[i - 1]) == clean(corrWords[j - 1])) {
        if (origWords[i - 1].equals(corrWords[j - 1], ignoreCase = true)) {
          result.add(DiffItem(DiffType.UNCHANGED, origWords[i - 1], corrWords[j - 1]))
        } else {
          result.add(DiffItem(DiffType.REPLACED, origWords[i - 1], corrWords[j - 1]))
        }
        i--
        j--
      } else if (i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + 1) {
        result.add(DiffItem(DiffType.REPLACED, origWords[i - 1], corrWords[j - 1]))
        i--
        j--
      } else if (i > 0 && dp[i][j] == dp[i - 1][j] + 1) {
        result.add(DiffItem(DiffType.DELETED, origWords[i - 1], ""))
        i--
      } else if (j > 0) {
        result.add(DiffItem(DiffType.INSERTED, "", corrWords[j - 1]))
        j--
      }
    }

    result.reverse()
    return result
  }
}
