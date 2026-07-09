package com.example.tes.data

import kotlinx.coroutines.flow.Flow

class SessionRepository(private val prefs: AppPreferences) {

    val distractingApps: Flow<Set<String>> = prefs.distractingApps
    val alarmSource: Flow<String> = prefs.alarmSource
    val alarmCustomPath: Flow<String?> = prefs.alarmCustomPath
    val sessionHistory: Flow<List<Session>> = prefs.sessionHistory

    suspend fun saveDistractingApps(apps: Set<String>) = prefs.setDistractingApps(apps)
    suspend fun saveAlarmSource(source: String) = prefs.setAlarmSource(source)
    suspend fun saveAlarmCustomPath(path: String?) = prefs.setAlarmCustomPath(path)
    suspend fun saveSession(session: Session) = prefs.addSession(session)
}
