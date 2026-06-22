package com.orbit.remote.ui

import androidx.lifecycle.ViewModel
import com.orbit.remote.domain.model.AgentState
import com.orbit.remote.service.AgentStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class AgentViewModel @Inject constructor(
    private val stateHolder: AgentStateHolder
) : ViewModel() {

    val state: StateFlow<AgentState> = stateHolder.state

    fun setAccessibilityEnabled(enabled: Boolean) =
        stateHolder.update { it.copy(accessibilityEnabled = enabled) }

    fun setBatteryOptimizationIgnored(ignored: Boolean) =
        stateHolder.update { it.copy(batteryOptimizationIgnored = ignored) }

    fun setMediaProjectionGranted(granted: Boolean) =
        stateHolder.update { it.copy(mediaProjectionGranted = granted) }
}
