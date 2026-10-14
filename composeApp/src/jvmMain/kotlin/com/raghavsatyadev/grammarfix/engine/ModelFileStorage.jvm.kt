package com.raghavsatyadev.grammarfix.engine

import java.io.File
import java.io.FileOutputStream

actual fun modelStorageDir(): String {
  val dir = File(System.getProperty("user.home") ?: ".", ".grammarfix")
  if (!dir.exists()) dir.mkdirs()
  return dir.absolutePath
}

actual fun getModelFileSize(path: String): Long {
  val file = File(path)
  return if (file.exists()) file.length() else 0L
}

actual fun appendModelFileChunk(path: String, bytes: ByteArray, length: Int) {
  try {
    val file = File(path)
    file.parentFile?.mkdirs()
    FileOutputStream(file, true).use { fos -> fos.write(bytes, 0, length) }
  } catch (e: Exception) {
    e.printStackTrace()
  }
}

actual fun truncateModelFile(path: String) {
  try {
    val file = File(path)
    file.parentFile?.mkdirs()
    FileOutputStream(file, false).use { /* opening without append empties the file */ }
  } catch (e: Exception) {
    e.printStackTrace()
  }
}

actual fun removeModelFile(path: String) {
  try {
    val file = File(path)
    if (file.exists()) {
      file.delete()
    }
  } catch (e: Exception) {
    e.printStackTrace()
  }
}
