package com.jhellerstedt.zulip.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ZulipPreferences(
    private val dataStore: DataStore<Preferences>,
) {
    val showNotificationAsterisk: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[SHOW_NOTIFICATION_ASTERISK] ?: true }

    suspend fun setShowNotificationAsterisk(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[SHOW_NOTIFICATION_ASTERISK] = enabled
        }
    }

    private companion object {
        val SHOW_NOTIFICATION_ASTERISK = booleanPreferencesKey("notification_asterisk")
    }
}
