package app.decentralert.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SosCoordinatorTest {
    @Test
    fun timeoutTransitionsToSendingExactlyOnce() {
        val coordinator = SosCoordinator(countdownSeconds = 2)
        coordinator.activate(EmergencyCategory.MEDICAL)
        assertFalse(coordinator.tick())
        assertTrue(coordinator.tick())
        assertFalse(coordinator.tick())
        assertEquals(EmergencyCategory.MEDICAL, assertIs<SosState.Sending>(coordinator.state.value).category)
    }

    @Test
    fun cancellationPreventsTimeout() {
        val coordinator = SosCoordinator()
        coordinator.activate()
        coordinator.cancel()
        assertIs<SosState.Cancelled>(coordinator.state.value)
        assertFalse(coordinator.tick())
    }

    @Test
    fun sendNowSkipsCountdown() {
        val coordinator = SosCoordinator()
        coordinator.activate(EmergencyCategory.FIRE)
        assertTrue(coordinator.sendNow())
        assertEquals(EmergencyCategory.FIRE, assertIs<SosState.Sending>(coordinator.state.value).category)
    }
}

