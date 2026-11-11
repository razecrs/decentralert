use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum EmergencyCategory {
    Police,
    Fire,
    Medical,
    Rescue,
    MultiService,
    Unknown,
}

impl EmergencyCategory {
    pub fn services(self) -> Vec<EmergencyService> {
        match self {
            Self::Police => vec![EmergencyService::Police],
            Self::Fire => vec![EmergencyService::Fire],
            Self::Medical => vec![EmergencyService::Medical],
            Self::Rescue => vec![EmergencyService::Rescue],
            Self::MultiService | Self::Unknown => EmergencyService::ALL.to_vec(),
        }
    }
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum EmergencyService {
    Police,
    Fire,
    Medical,
    Rescue,
}

impl EmergencyService {
    pub const ALL: [Self; 4] = [Self::Police, Self::Fire, Self::Medical, Self::Rescue];
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum IncidentStatus {
    Dispatching,
    Acknowledged,
    ResponderEnRoute,
    Cancelled,
    Closed,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct GeoPoint {
    pub latitude: f64,
    pub longitude: f64,
    pub accuracy_meters: f32,
}

impl GeoPoint {
    pub fn validate(&self) -> Result<(), &'static str> {
        if !(-90.0..=90.0).contains(&self.latitude) {
            return Err("latitude must be between -90 and 90");
        }
        if !(-180.0..=180.0).contains(&self.longitude) {
            return Err("longitude must be between -180 and 180");
        }
        if !self.accuracy_meters.is_finite() || self.accuracy_meters < 0.0 {
            return Err("accuracyMeters must be non-negative");
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct EmergencyProfile {
    pub display_name: String,
    pub date_of_birth: Option<String>,
    #[serde(default)]
    pub allergies: Vec<String>,
    #[serde(default)]
    pub medications: Vec<String>,
    #[serde(default)]
    pub conditions: Vec<String>,
    #[serde(default)]
    pub accessibility_needs: Vec<String>,
    pub blood_type_self_reported: Option<String>,
    #[serde(default)]
    pub emergency_contacts: Vec<EmergencyContact>,
    pub preferred_language: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct EmergencyContact {
    pub name: String,
    pub phone: String,
    pub relationship: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SensorContext {
    pub captured_at: DateTime<Utc>,
    pub motion_summary: Option<String>,
    pub battery_percent: Option<u8>,
    pub network_type: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct EvidenceReference {
    pub object_key: String,
    pub media_type: String,
    pub camera: Option<String>,
    pub sha256: String,
    pub consent_version: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct CreateIncidentRequest {
    pub idempotency_key: String,
    pub category: EmergencyCategory,
    pub location: GeoPoint,
    pub description: Option<String>,
    pub profile: EmergencyProfile,
    #[serde(default)]
    pub evidence: Vec<EvidenceReference>,
    pub sensor_context: Option<SensorContext>,
}

impl CreateIncidentRequest {
    pub fn validate(&self) -> Result<(), &'static str> {
        if self.idempotency_key.trim().is_empty() || self.idempotency_key.len() > 128 {
            return Err("idempotencyKey must contain 1-128 characters");
        }
        self.location.validate()?;
        if self.profile.display_name.trim().is_empty() {
            return Err("profile.displayName is required");
        }
        if self.evidence.len() > 4 {
            return Err("at most four evidence items are accepted");
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct DispatchAttempt {
    pub service: EmergencyService,
    pub adapter: String,
    pub status: DispatchStatus,
    pub attempted_at: DateTime<Utc>,
    pub acknowledged_at: Option<DateTime<Utc>>,
    pub reference: Option<String>,
    pub error: Option<String>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum DispatchStatus {
    Pending,
    Acknowledged,
    Failed,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ResponderOffer {
    pub responder_id: Uuid,
    pub display_name: String,
    pub distance_meters: u32,
    pub estimated_arrival_seconds: u32,
    pub status: OfferStatus,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum OfferStatus {
    Offered,
    Accepted,
    Declined,
    Expired,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Incident {
    pub id: Uuid,
    pub idempotency_key: String,
    pub category: EmergencyCategory,
    pub status: IncidentStatus,
    pub location: GeoPoint,
    pub description: Option<String>,
    pub profile: EmergencyProfile,
    pub evidence: Vec<EvidenceReference>,
    pub sensor_context: Option<SensorContext>,
    pub dispatch_attempts: Vec<DispatchAttempt>,
    pub responder_offers: Vec<ResponderOffer>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct VerifiedResponder {
    pub id: Uuid,
    pub display_name: String,
    pub capabilities: Vec<String>,
    pub location: GeoPoint,
    pub available: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PublicWarningRequest {
    pub title: String,
    pub instructions: String,
    pub category: EmergencyCategory,
    pub center: GeoPoint,
    pub radius_meters: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PublicWarning {
    pub id: Uuid,
    #[serde(flatten)]
    pub request: PublicWarningRequest,
    pub created_at: DateTime<Utc>,
    pub issued_by: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DomainEvent {
    pub event_id: Uuid,
    pub event_type: String,
    pub incident_id: Option<Uuid>,
    pub occurred_at: DateTime<Utc>,
    pub payload: serde_json::Value,
}

impl DomainEvent {
    pub fn incident(event_type: &str, incident: &Incident) -> Self {
        Self {
            event_id: Uuid::new_v4(),
            event_type: event_type.into(),
            incident_id: Some(incident.id),
            occurred_at: Utc::now(),
            payload: serde_json::to_value(incident).unwrap_or_default(),
        }
    }
}
