package dev.fortress.guard

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Global bridge for RealtimeGuard events, consumed by the Shield tab.
 * The guard service emits here; the console collects this singleton stream
 * so the feed survives tab switches and service restarts.
 */
object GuardCenter {

    private val _events = MutableSharedFlow<GuardEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GuardEvent> = _events

    fun emit(event: GuardEvent) {
        _events.tryEmit(event)
    }
}
