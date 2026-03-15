package dev.hapax.watch.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

/** Single DataStore instance for the entire app. Must be defined once at top level. */
val Context.dataStore by preferencesDataStore(name = "settings")
