package dev.fortress.net

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live telemetry from the packet tap, consumed by the Network tab.
 *
 * WHY a bridge: TrafficMonitor's event flow lives inside PacketVpnService
 * (the service owns the monitor), while the UI must survive tab switches and
 * rotation. The service forwards every classified packet here; the console
 * collects this singleton stream instead of reaching into the service.
 */
object TapCenter {

    private val _events = MutableSharedFlow<TrafficMonitor.PacketEvent>(replay = 60, extraBufferCapacity = 256)
    val events: SharedFlow<TrafficMonitor.PacketEvent> = _events

    private val _total = MutableStateFlow(0)
    val total: StateFlow<Int> = _total

    private val _flagged = MutableStateFlow(0)
    val flagged: StateFlow<Int> = _flagged

    private val _blocked = MutableStateFlow(0)
    val blocked: StateFlow<Int> = _blocked

    /** Called from the service's packet path (drop-on-overflow is fine — advisory feed). */
    fun emit(event: TrafficMonitor.PacketEvent) {
        _events.tryEmit(event)
        _total.value += 1
        when (event.verdict) {
            "blocked" -> _blocked.value += 1
            "flagged" -> _flagged.value += 1
        }
    }

    /** Clears the session counters + feed (called when the tap stops). */
    fun reset() {
        _total.value = 0
        _flagged.value = 0
        _blocked.value = 0
    }
}
