package com.raghavsatyadev.grammarfix.engine

actual fun getCurrentRamUsageMb(): Float {
  val runtime = Runtime.getRuntime()
  val usedBytes = runtime.totalMemory() - runtime.freeMemory()
  return (usedBytes / (1024f * 1024f))
}

actual fun getTotalRamMb(): Float {
  val runtime = Runtime.getRuntime()
  return (runtime.maxMemory() / (1024f * 1024f))
}
