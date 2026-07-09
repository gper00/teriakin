package com.example.tes.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "focus_prefs")

class AppPreferences(private val context: Context) {

    companion object {
        private val KEY_DISTRACTING_APPS = stringPreferencesKey("distracting_apps")
        private val KEY_ALARM_SOURCE = stringPreferencesKey("alarm_source")
        private val KEY_ALARM_PATH = stringPreferencesKey("alarm_custom_path")
        private val KEY_SESSION_HISTORY = stringPreferencesKey("session_history")

        const val ALARM_SOURCE_BUILTIN = "builtin"
        const val ALARM_SOURCE_FILE = "file"
        const val ALARM_SOURCE_RECORDING = "recording"
    }

    val distractingApps: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_DISTRACTING_APPS]?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    suspend fun setDistractingApps(apps: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DISTRACTING_APPS] = apps.joinToString(",")
        }
    }

    val alarmSource: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_ALARM_SOURCE] ?: ALARM_SOURCE_BUILTIN
    }

    suspend fun setAlarmSource(source: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ALARM_SOURCE] = source
        }
    }

    val alarmCustomPath: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_ALARM_PATH]
    }

    suspend fun setAlarmCustomPath(path: String?) {
        context.dataStore.edit { prefs ->
            if (path != null) prefs[KEY_ALARM_PATH] = path else prefs.remove(KEY_ALARM_PATH)
        }
    }

    val sessionHistory: Flow<List<Session>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_SESSION_HISTORY]
        if (json.isNullOrBlank()) emptyList()
        else deserializeSessions(json)
    }

    suspend fun addSession(session: Session) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_SESSION_HISTORY]
                ?.let { deserializeSessions(it) }
                ?: emptyList()
            val updated = (current + session).takeLast(100)
            prefs[KEY_SESSION_HISTORY] = serializeSessions(updated)
        }
    }

    private fun serializeSessions(sessions: List<Session>): String {
        return sessions.joinToString("|") { s ->
            "${s.startTimeMillis},${s.durationSeconds},${s.distractions},${if (s.completed) "1" else "0"}"
        }
    }

    private fun deserializeSessions(json: String): List<Session> {
        return json.split("|").filter { it.isNotBlank() }.map { entry ->
            val parts = entry.split(",")
            Session(
                startTimeMillis = parts[0].toLong(),
                durationSeconds = parts[1].toInt(),
                distractions = parts[2].toInt(),
                completed = parts[3] == "1"
            )
        }
    }
}
