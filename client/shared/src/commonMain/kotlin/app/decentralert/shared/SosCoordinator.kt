package app.decentralert.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SosState {
    data object Idle : SosState
    data class Countdown(val secondsRemaining: Int, val category: EmergencyCategory) : SosState
    data class Sending(val category: EmergencyCategory) : SosState
    data class Sent(val incidentId: String) : SosState
    data object Cancelled : SosState
    data class Failed(val message: String) : SosState
}

/**
 * Pure shared SOS state machine. Platform UIs own camera/location permission prompts and call [tick]
 * once per second. Timeout and explicit send converge on the same dispatch path.
 */
class SosCoordinator(private val countdownSeconds: Int = 30) {
    init { require(countdownSeconds in 1..120) }

    private val mutableState = MutableStateFlow<SosState>(SosState.Idle)
    val state: StateFlow<SosState> = mutableState.asStateFlow()

    fun activate(category: EmergencyCategory = EmergencyCategory.UNKNOWN) {
        check(mutableState.value is SosState.Idle || mutableState.value is SosState.Cancelled || mutableState.value is SosState.Failed)
        mutableState.value = SosState.Countdown(countdownSeconds, category)
    }

    fun selectCategory(category: EmergencyCategory) {
        val current = mutableState.value as? SosState.Countdown ?: return
        mutableState.value = current.copy(category = category)
    }

    /** Returns true exactly once when timeout requires incident submission. */
    fun tick(): Boolean {
        val current = mutableState.value as? SosState.Countdown ?: return false
        return if (current.secondsRemaining <= 1) {
            mutableState.value = SosState.Sending(current.category)
            true
        } else {
            mutableState.value = current.copy(secondsRemaining = current.secondsRemaining - 1)
            false
        }
    }

    /** Returns true when the caller should immediately submit the incident. */
    fun sendNow(): Boolean {
        val current = mutableState.value as? SosState.Countdown ?: return false
        mutableState.value = SosState.Sending(current.category)
        return true
    }

    fun cancel() {
        if (mutableState.value is SosState.Countdown) mutableState.value = SosState.Cancelled
    }

    fun markSent(incidentId: String) {
        check(mutableState.value is SosState.Sending)
        mutableState.value = SosState.Sent(incidentId)
    }

    fun markFailed(message: String) {
        check(mutableState.value is SosState.Sending)
        mutableState.value = SosState.Failed(message)
    }

    fun reset() { mutableState.value = SosState.Idle }
}

