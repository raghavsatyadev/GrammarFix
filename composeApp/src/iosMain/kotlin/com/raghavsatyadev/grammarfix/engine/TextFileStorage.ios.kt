@file:OptIn(ExperimentalForeignApi::class)

package com.raghavsatyadev.grammarfix.engine

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

actual fun readTextFileOrNull(path: String): String? =
  NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)

actual fun writeTextFile(path: String, text: String) {
  @Suppress("CAST_NEVER_SUCCEEDS")
  (text as NSString).writeToFile(
    path,
    atomically = true,
    encoding = NSUTF8StringEncoding,
    error = null,
  )
}

actual fun renameFile(from: String, to: String): Boolean {
  val fileManager = NSFileManager.defaultManager
  if (!fileManager.fileExistsAtPath(from)) return false
  fileManager.removeItemAtPath(to, error = null)
  return fileManager.moveItemAtPath(from, toPath = to, error = null)
}
