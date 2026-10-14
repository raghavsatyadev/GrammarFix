package com.raghavsatyadev.grammarfix.engine

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import org.koin.mp.KoinPlatform

/**
 * The app's private files directory, resolved from the Android context.
 *
 * Resolving it at runtime matters: a hardcoded `/data/user/0/<pkg>/files` path is wrong for
 * secondary users and work profiles, where the app lives under `/data/user/<id>/`. Falls back to
 * the JVM temp dir so unit tests can run without a started Koin graph.
 */
actual fun modelStorageDir(): String {
  return try {
    KoinPlatform.getKoin().get<Context>().filesDir.absolutePath
  } catch (e: Exception) {
    System.getProperty("java.io.tmpdir") ?: "."
  }
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
