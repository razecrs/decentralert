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
    val sourceSessionId: String? = null,
    val emergencyCircleNotified: Int = 0,
    val offlineEscalation: Boolean = false,
    val privateIncident: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class DeviceRegistration(
    val deviceId: String,
    val deviceToken: String,
)

@Serializable
enum class NotificationChannel {
    @SerialName("call") CALL,
    @SerialName("sms") SMS,
    @SerialName("push") PUSH,
}

@Serializable
data class CircleMember(
    val id: String,
    val name: String,
    val phone: String,
    val relationship: String? = null,
    val channels: List<NotificationChannel> = listOf(NotificationChannel.CALL, NotificationChannel.SMS),
    val accepted: Boolean = false,
    val priority: Int = 0,
)

@Serializable
data class VaultConsent(
    val sharePreciseLocation: Boolean = true,
    val shareMedicalProfile: Boolean = true,
    val consentedSnapshots: Boolean = false,
    val consentedAudio: Boolean = false,
    val consentVersion: String = "2026-11-prototype",
)

@Serializable
data class EscalationPolicy(
    val notifyCircleOnConfirm: Boolean = true,
    val escalateWhenHeartbeatLost: Boolean = false,
    val heartbeatTimeoutSeconds: Int = 15,
)

@Serializable
data class EmergencyVault(
    val profile: EmergencyProfile,
    val circle: List<CircleMember> = emptyList(),
    val consent: VaultConsent = VaultConsent(),
    val escalation: EscalationPolicy = EscalationPolicy(),
    val updatedAt: String,
)

@Serializable
data class CreateSosSessionRequest(
    val category: EmergencyCategory,
    val location: GeoPoint,
    val description: String? = null,
    val sensorContext: SensorContext? = null,
)

@Serializable
data class HeartbeatRequest(
    val location: GeoPoint? = null,
    val batteryPercent: Int? = null,
)

@Serializable
enum class SosSessionStatus {
    @SerialName("pending_confirmation") PENDING_CONFIRMATION,
    @SerialName("confirmed") CONFIRMED,
    @SerialName("escalated_offline") ESCALATED_OFFLINE,
    @SerialName("cancelled") CANCELLED,
}

@Serializable
data class CircleNotificationAttempt(
    val memberId: String,
    val memberName: String,
    val channels: List<NotificationChannel>,
    val status: String,
    val attemptedAt: String,
)

@Serializable
data class SosSession(
    val id: String,
    val deviceId: String,
    val status: SosSessionStatus,
    val category: EmergencyCategory,
    val location: GeoPoint,
    val description: String? = null,
    val sensorContext: SensorContext? = null,
    val vaultSnapshot: EmergencyVault? = null,
    val notificationAttempts: List<CircleNotificationAttempt> = emptyList(),
    val incidentId: String? = null,
    val confirmationDeadline: String,
    val lastHeartbeatAt: String,
    val createdAt: String,
    val updatedAt: String,
)

