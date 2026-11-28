package com.example.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class NetworkEvent(
    val id: Long = com.example.runtime.MonotonicIdGenerator.nextId(),
    val timestamp: Long = System.currentTimeMillis(),
    val destination: String,
    val purpose: String,
    val status: String,
    val bytesTransferred: Long = 0L
)

class NetworkAuditLogger {
    private val _events = MutableStateFlow<List<NetworkEvent>>(emptyList())
    val events: StateFlow<List<NetworkEvent>> = _events.asStateFlow()

    fun log(destination: String, purpose: String, status: String, bytesTransferred: Long = 0L) {
        val event = NetworkEvent(
            destination = destination,
            purpose = purpose,
            status = status,
            bytesTransferred = bytesTransferred
        )
        _events.update { current -> (listOf(event) + current).take(100) }
    }

    fun clear() {
        _events.value = emptyList()
    }
}
