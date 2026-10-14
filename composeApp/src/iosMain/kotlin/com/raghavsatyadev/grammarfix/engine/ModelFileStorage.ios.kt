@file:OptIn(ExperimentalForeignApi::class)

package com.raghavsatyadev.grammarfix.engine

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.seekToEndOfFile
import platform.Foundation.writeData

actual fun modelStorageDir(): String {
  val paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
  return (paths.firstOrNull() as? String) ?: NSTemporaryDirectory()
}

actual fun getModelFileSize(path: String): Long {
  val fileManager = NSFileManager.defaultManager
  if (!fileManager.fileExistsAtPath(path)) return 0L
  val attributes = fileManager.attributesOfItemAtPath(path, error = null) ?: return 0L
  // NSFileSize arrives as an NSNumber, not a Kotlin Long.
  return (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: 0L
}

actual fun appendModelFileChunk(path: String, bytes: ByteArray, length: Int) {
  if (length <= 0) return
  val fileManager = NSFileManager.defaultManager
  if (!fileManager.fileExistsAtPath(path)) {
    fileManager.createFileAtPath(path, contents = null, attributes = null)
  }
  val handle = NSFileHandle.fileHandleForWritingAtPath(path) ?: return
  try {
    handle.seekToEndOfFile()
    bytes.usePinned { pinned ->
      handle.writeData(NSData.create(bytes = pinned.addressOf(0), length = length.toULong()))
    }
  } finally {
    handle.closeFile()
  }
}

actual fun truncateModelFile(path: String) {
  val fileManager = NSFileManager.defaultManager
  if (fileManager.fileExistsAtPath(path)) {
    fileManager.removeItemAtPath(path, error = null)
  }
  fileManager.createFileAtPath(path, contents = null, attributes = null)
}

actual fun removeModelFile(path: String) {
  NSFileManager.defaultManager.removeItemAtPath(path, error = null)
}
