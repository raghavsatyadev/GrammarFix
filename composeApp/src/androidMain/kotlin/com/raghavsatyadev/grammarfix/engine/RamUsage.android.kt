package com.raghavsatyadev.grammarfix.engine

import java.io.File

actual fun getCurrentRamUsageMb(): Float {
  try {
    val file = File("/proc/meminfo")
    if (file.exists()) {
      var totalKb = 0L
      var availableKb = 0L
      file.forEachLine { line ->
        if (line.startsWith("MemTotal:")) {
          totalKb = line.substringAfter(":").trim().substringBefore(" ").trim().toLongOrNull() ?: 0L
        } else if (line.startsWith("MemAvailable:")) {
          availableKb =
            line.substringAfter(":").trim().substringBefore(" ").trim().toLongOrNull() ?: 0L
        }
      }
      if (totalKb > 0L && availableKb > 0L) {
        val usedKb = totalKb - availableKb
        return usedKb / 1024f
      }
    }
  } catch (_: Exception) {}

  val runtime = Runtime.getRuntime()
  val usedBytes = runtime.totalMemory() - runtime.freeMemory()
  return (usedBytes / (1024f * 1024f))
}

actual fun getTotalRamMb(): Float {
  try {
    val file = File("/proc/meminfo")
    if (file.exists()) {
      var totalKb = 0L
      file.forEachLine { line ->
        if (line.startsWith("MemTotal:")) {
          totalKb = line.substringAfter(":").trim().substringBefore(" ").trim().toLongOrNull() ?: 0L
        }
      }
      if (totalKb > 0L) {
        return totalKb / 1024f
      }
    }
  } catch (_: Exception) {}

  val runtime = Runtime.getRuntime()
  return (runtime.maxMemory() / (1024f * 1024f))
}
