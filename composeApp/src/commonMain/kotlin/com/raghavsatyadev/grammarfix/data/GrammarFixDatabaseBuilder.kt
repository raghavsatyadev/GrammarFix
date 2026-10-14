package com.raghavsatyadev.grammarfix.data

import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.coroutines.CoroutineContext

/** File name of the database on every platform. */
const val GRAMMARFIX_DATABASE_NAME = "grammarfix.db"

/** Where the database file lives; the only part of it that differs per platform. */
expect fun grammarFixDatabaseBuilder(): RoomDatabase.Builder<GrammarFixDatabase>

/**
 * What Room runs queries on. The bundled driver blocks while it reads the file, so this is
 * `Dispatchers.IO` where there is one; Kotlin/Native does not expose one, and falls back to
 * `Dispatchers.Default`.
 */
expect val databaseQueryContext: CoroutineContext

/**
 * Opens the database.
 *
 * [BundledSQLiteDriver] ships SQLite with the app rather than using whatever the OS provides, so
 * Android, iOS and the JVM all run the same engine and the same SQL.
 */
fun openGrammarFixDatabase(
  builder: RoomDatabase.Builder<GrammarFixDatabase> = grammarFixDatabaseBuilder()
): GrammarFixDatabase =
  builder.setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(databaseQueryContext).build()
