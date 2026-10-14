package com.raghavsatyadev.grammarfix.data

import androidx.room3.Room
import androidx.room3.RoomDatabase
import com.raghavsatyadev.grammarfix.engine.modelStorageDir
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

/** Beside the model and `tone_presets.json`, so every file the app owns sits in one place. */
actual fun grammarFixDatabaseBuilder(): RoomDatabase.Builder<GrammarFixDatabase> =
  Room.databaseBuilder<GrammarFixDatabase>(name = "${modelStorageDir()}/$GRAMMARFIX_DATABASE_NAME")

actual val databaseQueryContext: CoroutineContext = Dispatchers.Default
