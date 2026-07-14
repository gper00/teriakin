package com.example.tes.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tes.data.AppPreferences
import com.example.tes.data.Session
import com.example.tes.data.SessionRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class SessionEvent {
    data object Completed : SessionEvent()
}

enum class TimerState { IDLE, RUNNING, PAUSED, COMPLETED, INTERRUPTED }
enum class Screen { HOME, SETUP, HISTORY }

class FocusViewModel(application: Application) : AndroidViewModel(application) {

    val repository = SessionRepository(AppPreferences(application))

    private val _timerState = MutableStateFlow(TimerState.IDLE)
    val timerState: StateFlow<TimerState> = _timerState.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()

    private val _sessionDuration = MutableStateFlow(1500)
    val sessionDuration: StateFlow<Int> = _sessionDuration.asStateFlow()

    private val _distractionCount = MutableStateFlow(0)
    val distractionCount: StateFlow<Int> = _distractionCount.asStateFlow()

    private val _currentScreen = MutableStateFlow(Screen.HOME)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    private var sessionStartTime: Long = 0L
    private var timerJob: Job? = null

    fun setDuration(seconds: Int) {
        if (_timerState.value == TimerState.IDLE) {
            _sessionDuration.value = seconds
        }
    }

    fun startSession() {
        if (_timerState.value != TimerState.IDLE) return
        _timerState.value = TimerState.RUNNING
        _elapsedSeconds.value = 0
        _distractionCount.value = 0
        sessionStartTime = System.currentTimeMillis()

        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val elapsed = _elapsedSeconds.value + 1
                _elapsedSeconds.value = elapsed
                if (elapsed >= _sessionDuration.value) {
                    completeSession(true)
                    return@launch
                }
            }
        }
    }

    fun pauseSession() {
        if (_timerState.value == TimerState.RUNNING) {
            _timerState.value = TimerState.PAUSED
            timerJob?.cancel()
        }
    }

    fun resumeSession() {
        if (_timerState.value == TimerState.PAUSED) {
            _timerState.value = TimerState.RUNNING
            timerJob = viewModelScope.launch {
                while (true) {
                    delay(1000)
                    val elapsed = _elapsedSeconds.value + 1
                    _elapsedSeconds.value = elapsed
                    if (elapsed >= _sessionDuration.value) {
                        completeSession(true)
                        return@launch
                    }
                }
            }
        }
    }

    fun stopSession() {
        timerJob?.cancel()
        saveSession(completed = false)
        resetState()
        _timerState.value = TimerState.IDLE
    }

    fun triggerDistraction() {
        _distractionCount.value = _distractionCount.value + 1
        _timerState.value = TimerState.INTERRUPTED
    }

    fun dismissAlarm() {
        if (_timerState.value == TimerState.INTERRUPTED) {
            _timerState.value = TimerState.RUNNING
            if (timerJob?.isActive != true) {
                timerJob = viewModelScope.launch {
                    while (true) {
                        delay(1000)
                        val elapsed = _elapsedSeconds.value + 1
                        _elapsedSeconds.value = elapsed
                        if (elapsed >= _sessionDuration.value) {
                            completeSession(true)
                            return@launch
                        }
                    }
                }
            }
        }
    }

    fun abortSessionFromAlarm() {
        timerJob?.cancel()
        saveSession(completed = false)
        resetState()
        _timerState.value = TimerState.IDLE
    }

    private fun completeSession(completed: Boolean) {
        timerJob?.cancel()
        saveSession(completed)
        resetState()
        _timerState.value = TimerState.IDLE
        if (completed) {
            _events.tryEmit(SessionEvent.Completed)
        }
    }

    private fun saveSession(completed: Boolean) {
        viewModelScope.launch {
            repository.saveSession(
                Session(
                    startTimeMillis = sessionStartTime,
                    durationSeconds = _elapsedSeconds.value,
                    distractions = _distractionCount.value,
                    completed = completed
                )
            )
        }
    }

    private fun resetState() {
        _elapsedSeconds.value = 0
        _distractionCount.value = 0
        sessionStartTime = 0L
    }

    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
    }

    fun formatTime(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format("%02d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
    }
}
