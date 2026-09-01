package jp.rimtty.codematch.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

private const val SETTINGS_FILE_NAME = "codematch-settings"

/**
 * One process-wide Preferences DataStore for Code Match settings.
 *
 * The delegate must be declared once at top level; creating another delegate
 * for the same file can make DataStore reject reads in the same process.
 */
val Context.codeMatchSettingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = SETTINGS_FILE_NAME)
