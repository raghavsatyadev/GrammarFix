package com.raghavsatyadev.grammarfix.engine

import java.io.File

actual fun readTextFileOrNull(path: String): String? =
  try {
    File(path).takeIf { it.exists() }?.readText()
  } catch (e: Exception) {
    e.printStackTrace()
    null
  }

actual fun writeTextFile(path: String, text: String) {
  try {
    val file = File(path)
    file.parentFile?.mkdirs()
    file.writeText(text)
  } catch (e: Exception) {
    e.printStackTrace()
  }
}

actual fun renameFile(from: String, to: String): Boolean =
  try {
    val source = File(from)
    if (!source.exists()) false
    else {
      File(to).delete()
      source.renameTo(File(to))
    }
  } catch (e: Exception) {
    e.printStackTrace()
    false
  }
