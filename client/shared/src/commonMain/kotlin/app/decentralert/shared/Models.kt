package app.decentralert.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class EmergencyCategory {
    @SerialName("police") POLICE,
    @SerialName("fire") FIRE,
    @SerialName("medical") MEDICAL,
    @SerialName("rescue") RESCUE,
    @SerialName("multi_service") MULTI_SERVICE,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
enum class IncidentStatus {
    @SerialName("dispatching") DISPATCHING,
    @SerialName("acknowledged") ACKNOWLEDGED,
    @SerialName("responder_en_route") RESPONDER_EN_ROUTE,
    @SerialName("cancelled") CANCELLED,
    @SerialName("closed") CLOSED,
}

@Serializable
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
)

@Serializable
data class EmergencyContact(
    val name: String,
    val phone: String,
    val relationship: String? = null,
)

@Serializable
data class EmergencyProfile(
    val displayName: String,
    val dateOfBirth: String? = null,
    val allergies: List<String> = emptyList(),
    val medications: List<String> = emptyList(),
    val conditions: List<String> = emptyList(),
    val accessibilityNeeds: List<String> = emptyList(),
    val bloodTypeSelfReported: String? = null,
    val emergencyContacts: List<EmergencyContact> = emptyList(),
    val preferredLanguage: String? = null,
)

@Serializable
data class SensorContext(
    val capturedAt: String,
    val motionSummary: String? = null,
    val batteryPercent: Int? = null,
    val networkType: String? = null,
)

@Serializable
data class EvidenceReference(
    val objectKey: String,
    val mediaType: String,
    val camera: String? = null,
    val sha256: String,
    val consentVersion: String,
)

@Serializable
data class CreateIncidentRequest(
    val idempotencyKey: String,
    val category: EmergencyCategory,
    val location: GeoPoint,
    val description: String? = null,
    val profile: EmergencyProfile,
    val evidence: List<EvidenceReference> = emptyList(),
    val sensorContext: SensorContext? = null,
)

@Serializable
data class DispatchAttempt(
    val service: String,
    val adapter: String,
    val status: String,
    val attemptedAt: String,
    val acknowledgedAt: String? = null,
    val reference: String? = null,
    val error: String? = null,
)

@Serializable
data class ResponderOffer(
    val responderId: String,
    val displayName: String,
    val distanceMeters: Int,
    val estimatedArrivalSeconds: Int,
    val status: String,
)

@Serializable
data class Incident(
    val id: String,
    val idempotencyKey: String,
    val category: EmergencyCategory,
    val status: IncidentStatus,
    val location: GeoPoint,
    val description: String? = null,
    val profile: EmergencyProfile,
    val evidence: List<EvidenceReference> = emptyList(),
    val sensorContext: SensorContext? = null,
    val dispatchAttempts: List<DispatchAttempt> = emptyList(),
    val responderOffers: List<ResponderOffer> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
)

