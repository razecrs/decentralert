package app.decentralert.shared

data class CaptureConsent(
    val version: String,
    val automaticSnapshotsEnabled: Boolean,
    val acceptedAtEpochMillis: Long,
)

interface EmergencyContextCollector {
    suspend fun currentLocation(): GeoPoint
    suspend fun collectSensorContext(): SensorContext?

    /** Captures front then rear while the SOS UI is visibly active. Never runs covertly. */
    suspend fun captureConsentedSnapshots(consent: CaptureConsent): List<EvidenceReference>

    /** Must remove local captures when SOS is cancelled before submission. */
    suspend fun discardPendingEvidence()
}

interface EmergencyDialer {
    /** Opens the platform-confirmed emergency call flow; it does not claim an automatic 112 call. */
    fun openEmergencyCall(number: String = "112")
}

