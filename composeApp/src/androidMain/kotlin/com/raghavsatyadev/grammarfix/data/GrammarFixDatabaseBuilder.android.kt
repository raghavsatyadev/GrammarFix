package com.raghavsatyadev.grammarfix.data

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import org.koin.mp.KoinPlatform

/**
 * The app's private databases directory, resolved from the Android context at runtime for the same
 * reason `modelStorageDir()` is: a hardcoded `/data/user/0/<pkg>` path is wrong on secondary users
 * and work profiles.
 *
 * Falls back to the JVM temp dir when there is no context in the graph, so previews and screenshot
 * tests — which start Koin without one — still render.
 */
actual fun grammarFixDatabaseBuilder(): RoomDatabase.Builder<GrammarFixDatabase> {
  val context =
    try {
      KoinPlatform.getKoin().get<Context>()
    } catch (e: Exception) {
      e.printStackTrace()
      null
    }
  return if (context != null) {
    Room.databaseBuilder<GrammarFixDatabase>(
      context = context,
      name = context.getDatabasePath(GRAMMARFIX_DATABASE_NAME).absolutePath,
    )
  } else {
    val directory = System.getProperty("java.io.tmpdir") ?: "."
    Room.databaseBuilder<GrammarFixDatabase>(name = "$directory/$GRAMMARFIX_DATABASE_NAME")
  }
}

actual val databaseQueryContext: CoroutineContext = Dispatchers.IO
