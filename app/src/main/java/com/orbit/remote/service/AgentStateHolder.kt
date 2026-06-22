package com.orbit.remote.service

import com.orbit.remote.domain.model.AgentState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Shared, observable agent state bridging the foreground service and the UI. */
@Singleton
class AgentStateHolder @Inject constructor() {
    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state.asStateFlow()

    fun update(transform: (AgentState) -> AgentState) = _state.update(transform)
}
