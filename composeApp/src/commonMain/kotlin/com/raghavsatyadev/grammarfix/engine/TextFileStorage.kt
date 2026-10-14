package com.raghavsatyadev.grammarfix.engine

/** Reads a small UTF-8 text file, or null when it is missing or cannot be read. */
expect fun readTextFileOrNull(path: String): String?

/** Writes [text] to [path] as UTF-8, replacing whatever was there. */
expect fun writeTextFile(path: String, text: String)

/**
 * Moves [from] onto [to], replacing whatever was at [to]. Returns false when the move did not
 * happen — including when [from] does not exist.
 */
expect fun renameFile(from: String, to: String): Boolean
