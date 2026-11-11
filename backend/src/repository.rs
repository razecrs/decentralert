use std::{collections::HashMap, sync::RwLock};

use async_trait::async_trait;
use sqlx::{PgPool, postgres::PgPoolOptions};
use uuid::Uuid;

use crate::{
    error::ApiError,
    models::{
        DeviceRecord, EmergencyVault, GeoPoint, InAppAlert, Incident, NetworkInvite,
        NetworkInviteStatus, PublicWarning, SosSession, VerifiedResponder,
    },
};

#[async_trait]
pub trait IncidentRepository: Send + Sync {
    async fn insert(&self, incident: Incident) -> Result<Incident, ApiError>;
    async fn update(&self, incident: Incident) -> Result<Incident, ApiError>;
    async fn get(&self, id: Uuid) -> Result<Option<Incident>, ApiError>;
    async fn by_idempotency_key(&self, key: &str) -> Result<Option<Incident>, ApiError>;
    async fn list(&self) -> Result<Vec<Incident>, ApiError>;
    async fn nearby_responders(
        &self,
        point: &GeoPoint,
        limit: usize,
    ) -> Result<Vec<(VerifiedResponder, u32)>, ApiError>;
    async fn save_warning(&self, warning: PublicWarning) -> Result<(), ApiError>;
    async fn register_device(&self, device: DeviceRecord) -> Result<(), ApiError>;
    async fn device(&self, id: Uuid) -> Result<Option<DeviceRecord>, ApiError>;
    async fn device_by_username(&self, username: &str) -> Result<Option<DeviceRecord>, ApiError>;
    async fn touch_device(&self, id: Uuid) -> Result<(), ApiError>;
    async fn insert_invite(&self, invite: NetworkInvite) -> Result<NetworkInvite, ApiError>;
    async fn network_invite(&self, id: Uuid) -> Result<Option<NetworkInvite>, ApiError>;
    async fn update_invite(&self, invite: NetworkInvite) -> Result<NetworkInvite, ApiError>;
    async fn incoming_invites(&self, device_id: Uuid) -> Result<Vec<NetworkInvite>, ApiError>;
    async fn accepted_connection(&self, a: Uuid, b: Uuid) -> Result<bool, ApiError>;
    async fn connections(&self, device_id: Uuid) -> Result<Vec<String>, ApiError>;
    async fn insert_alert(&self, alert: InAppAlert) -> Result<InAppAlert, ApiError>;
    async fn alerts(&self, device_id: Uuid) -> Result<Vec<InAppAlert>, ApiError>;
    async fn save_vault(
        &self,
        device_id: Uuid,
        vault: EmergencyVault,
    ) -> Result<EmergencyVault, ApiError>;
    async fn vault(&self, device_id: Uuid) -> Result<Option<EmergencyVault>, ApiError>;
    async fn insert_sos_session(&self, session: SosSession) -> Result<SosSession, ApiError>;
    async fn update_sos_session(&self, session: SosSession) -> Result<SosSession, ApiError>;
    async fn sos_session(&self, id: Uuid) -> Result<Option<SosSession>, ApiError>;
    async fn pending_sos_sessions(&self) -> Result<Vec<SosSession>, ApiError>;
}

#[derive(Default)]
pub struct MemoryRepository {
    incidents: RwLock<HashMap<Uuid, Incident>>,
    responders: RwLock<Vec<VerifiedResponder>>,
    warnings: RwLock<Vec<PublicWarning>>,
    devices: RwLock<HashMap<Uuid, DeviceRecord>>,
    invites: RwLock<HashMap<Uuid, NetworkInvite>>,
    alerts: RwLock<HashMap<Uuid, InAppAlert>>,
    vaults: RwLock<HashMap<Uuid, EmergencyVault>>,
    sos_sessions: RwLock<HashMap<Uuid, SosSession>>,
}

impl MemoryRepository {
    pub fn seeded() -> Self {
        let responders = vec![
            VerifiedResponder {
                id: Uuid::new_v4(),
                display_name: "Noor · First aid".into(),
                capabilities: vec!["first_aid".into(), "aed".into()],
                location: GeoPoint {
                    latitude: 52.3729,
                    longitude: 4.8936,
                    accuracy_meters: 8.0,
                },
                available: true,
            },
            VerifiedResponder {
                id: Uuid::new_v4(),
                display_name: "Sam · Community responder".into(),
                capabilities: vec!["first_aid".into()],
                location: GeoPoint {
                    latitude: 52.3702,
                    longitude: 4.8952,
                    accuracy_meters: 12.0,
                },
                available: true,
            },
            VerifiedResponder {
                id: Uuid::new_v4(),
                display_name: "Mila · Off duty".into(),
                capabilities: vec!["nurse".into()],
                location: GeoPoint {
                    latitude: 52.3690,
                    longitude: 4.9010,
                    accuracy_meters: 10.0,
                },
                available: false,
            },
        ];
        Self {
            incidents: RwLock::default(),
            responders: RwLock::new(responders),
            warnings: RwLock::default(),
            devices: RwLock::default(),
            invites: RwLock::default(),
            alerts: RwLock::default(),
            vaults: RwLock::default(),
            sos_sessions: RwLock::default(),
        }
    }
}

fn distance_meters(a: &GeoPoint, b: &GeoPoint) -> u32 {
    let earth = 6_371_000.0_f64;
    let d_lat = (b.latitude - a.latitude).to_radians();
    let d_lon = (b.longitude - a.longitude).to_radians();
    let lat1 = a.latitude.to_radians();
    let lat2 = b.latitude.to_radians();
    let h = (d_lat / 2.0).sin().powi(2) + lat1.cos() * lat2.cos() * (d_lon / 2.0).sin().powi(2);
    (earth * 2.0 * h.sqrt().asin()).round() as u32
}

#[async_trait]
impl IncidentRepository for MemoryRepository {
    async fn insert(&self, incident: Incident) -> Result<Incident, ApiError> {
        self.incidents
            .write()
            .unwrap()
            .insert(incident.id, incident.clone());
        Ok(incident)
    }
    async fn update(&self, incident: Incident) -> Result<Incident, ApiError> {
        self.incidents
            .write()
            .unwrap()
            .insert(incident.id, incident.clone());
        Ok(incident)
    }
    async fn get(&self, id: Uuid) -> Result<Option<Incident>, ApiError> {
        Ok(self.incidents.read().unwrap().get(&id).cloned())
    }
    async fn by_idempotency_key(&self, key: &str) -> Result<Option<Incident>, ApiError> {
        Ok(self
            .incidents
            .read()
            .unwrap()
            .values()
            .find(|i| i.idempotency_key == key)
            .cloned())
    }
    async fn list(&self) -> Result<Vec<Incident>, ApiError> {
        let mut items: Vec<_> = self.incidents.read().unwrap().values().cloned().collect();
        items.sort_by_key(|i| std::cmp::Reverse(i.created_at));
        Ok(items)
    }
    async fn nearby_responders(
        &self,
        point: &GeoPoint,
        limit: usize,
    ) -> Result<Vec<(VerifiedResponder, u32)>, ApiError> {
        let mut items: Vec<_> = self
            .responders
            .read()
            .unwrap()
            .iter()
            .filter(|r| r.available)
            .cloned()
            .map(|r| {
                let distance = distance_meters(point, &r.location);
                (r, distance)
            })
            .collect();
        items.sort_by_key(|(_, distance)| *distance);
        items.truncate(limit);
        Ok(items)
    }
    async fn save_warning(&self, warning: PublicWarning) -> Result<(), ApiError> {
        self.warnings.write().unwrap().push(warning);
        Ok(())
    }
    async fn register_device(&self, device: DeviceRecord) -> Result<(), ApiError> {
        self.devices.write().unwrap().insert(device.id, device);
        Ok(())
    }
    async fn device(&self, id: Uuid) -> Result<Option<DeviceRecord>, ApiError> {
        Ok(self.devices.read().unwrap().get(&id).cloned())
    }
    async fn device_by_username(&self, username: &str) -> Result<Option<DeviceRecord>, ApiError> {
        Ok(self
            .devices
            .read()
            .unwrap()
            .values()
            .find(|device| device.username == username)
            .cloned())
    }
    async fn touch_device(&self, id: Uuid) -> Result<(), ApiError> {
        if let Some(device) = self.devices.write().unwrap().get_mut(&id) {
            device.last_seen_at = chrono::Utc::now();
        }
        Ok(())
    }
    async fn insert_invite(&self, invite: NetworkInvite) -> Result<NetworkInvite, ApiError> {
        self.invites
            .write()
            .unwrap()
            .insert(invite.id, invite.clone());
        Ok(invite)
    }
    async fn network_invite(&self, id: Uuid) -> Result<Option<NetworkInvite>, ApiError> {
        Ok(self.invites.read().unwrap().get(&id).cloned())
    }
    async fn update_invite(&self, invite: NetworkInvite) -> Result<NetworkInvite, ApiError> {
        self.invites
            .write()
            .unwrap()
            .insert(invite.id, invite.clone());
        Ok(invite)
    }
    async fn incoming_invites(&self, device_id: Uuid) -> Result<Vec<NetworkInvite>, ApiError> {
        let mut items: Vec<_> = self
            .invites
            .read()
            .unwrap()
            .values()
            .filter(|invite| {
                invite.to_device_id == device_id && invite.status == NetworkInviteStatus::Pending
            })
            .cloned()
            .collect();
        items.sort_by_key(|invite| std::cmp::Reverse(invite.created_at));
        Ok(items)
    }
    async fn accepted_connection(&self, a: Uuid, b: Uuid) -> Result<bool, ApiError> {
        Ok(self.invites.read().unwrap().values().any(|invite| {
            invite.status == NetworkInviteStatus::Accepted
                && ((invite.from_device_id == a && invite.to_device_id == b)
                    || (invite.from_device_id == b && invite.to_device_id == a))
        }))
    }
    async fn connections(&self, device_id: Uuid) -> Result<Vec<String>, ApiError> {
        let devices = self.devices.read().unwrap();
        let mut usernames: Vec<_> = self
            .invites
            .read()
            .unwrap()
            .values()
            .filter(|invite| {
                invite.status == NetworkInviteStatus::Accepted
                    && (invite.from_device_id == device_id || invite.to_device_id == device_id)
            })
            .filter_map(|invite| {
                let other = if invite.from_device_id == device_id {
                    invite.to_device_id
                } else {
                    invite.from_device_id
                };
                devices.get(&other).map(|device| device.username.clone())
            })
            .collect();
        usernames.sort();
        usernames.dedup();
        Ok(usernames)
    }
    async fn insert_alert(&self, alert: InAppAlert) -> Result<InAppAlert, ApiError> {
        self.alerts.write().unwrap().insert(alert.id, alert.clone());
        Ok(alert)
    }
    async fn alerts(&self, device_id: Uuid) -> Result<Vec<InAppAlert>, ApiError> {
        let mut items: Vec<_> = self
            .alerts
            .read()
            .unwrap()
            .values()
            .filter(|alert| alert.recipient_device_id == device_id)
            .cloned()
            .collect();
        items.sort_by_key(|alert| std::cmp::Reverse(alert.created_at));
        items.truncate(50);
        Ok(items)
    }
    async fn save_vault(
        &self,
        device_id: Uuid,
        vault: EmergencyVault,
    ) -> Result<EmergencyVault, ApiError> {
        self.vaults
            .write()
            .unwrap()
            .insert(device_id, vault.clone());
        Ok(vault)
    }
    async fn vault(&self, device_id: Uuid) -> Result<Option<EmergencyVault>, ApiError> {
        Ok(self.vaults.read().unwrap().get(&device_id).cloned())
    }
    async fn insert_sos_session(&self, session: SosSession) -> Result<SosSession, ApiError> {
        self.sos_sessions
            .write()
            .unwrap()
            .insert(session.id, session.clone());
        Ok(session)
    }
    async fn update_sos_session(&self, session: SosSession) -> Result<SosSession, ApiError> {
        self.sos_sessions
            .write()
            .unwrap()
            .insert(session.id, session.clone());
        Ok(session)
    }
    async fn sos_session(&self, id: Uuid) -> Result<Option<SosSession>, ApiError> {
        Ok(self.sos_sessions.read().unwrap().get(&id).cloned())
    }
    async fn pending_sos_sessions(&self) -> Result<Vec<SosSession>, ApiError> {
        Ok(self
            .sos_sessions
            .read()
            .unwrap()
            .values()
            .filter(|session| {
                session.status == crate::models::SosSessionStatus::PendingConfirmation
            })
            .cloned()
            .collect())
    }
}

pub struct PostgresRepository {
    pool: PgPool,
}

impl PostgresRepository {
    pub async fn connect(url: &str) -> Result<Self, ApiError> {
        let pool = PgPoolOptions::new()
            .max_connections(10)
            .connect(url)
            .await?;
        sqlx::migrate!("./migrations")
            .run(&pool)
            .await
            .map_err(|e| ApiError::Storage(e.to_string()))?;
        Ok(Self { pool })
    }
    async fn upsert(&self, incident: Incident) -> Result<Incident, ApiError> {
        let payload =
            serde_json::to_value(&incident).map_err(|e| ApiError::Storage(e.to_string()))?;
        sqlx::query("INSERT INTO incidents (id,status,category,position,payload,created_at,updated_at) VALUES ($1,$2,$3,ST_SetSRID(ST_MakePoint($4,$5),4326)::geography,$6,$7,$8) ON CONFLICT (id) DO UPDATE SET status=EXCLUDED.status, category=EXCLUDED.category, position=EXCLUDED.position, payload=EXCLUDED.payload, updated_at=EXCLUDED.updated_at")
            .bind(incident.id).bind(format!("{:?}", incident.status).to_lowercase()).bind(format!("{:?}", incident.category).to_lowercase()).bind(incident.location.longitude).bind(incident.location.latitude).bind(payload).bind(incident.created_at).bind(incident.updated_at).execute(&self.pool).await?;
        Ok(incident)
    }
}

#[async_trait]
impl IncidentRepository for PostgresRepository {
    async fn insert(&self, incident: Incident) -> Result<Incident, ApiError> {
        self.upsert(incident).await
    }
    async fn update(&self, incident: Incident) -> Result<Incident, ApiError> {
        self.upsert(incident).await
    }
    async fn get(&self, id: Uuid) -> Result<Option<Incident>, ApiError> {
        let row: Option<(serde_json::Value,)> =
            sqlx::query_as("SELECT payload FROM incidents WHERE id=$1")
                .bind(id)
                .fetch_optional(&self.pool)
                .await?;
        row.map(|(v,)| serde_json::from_value(v).map_err(|e| ApiError::Storage(e.to_string())))
            .transpose()
    }
    async fn by_idempotency_key(&self, key: &str) -> Result<Option<Incident>, ApiError> {
        let row: Option<(serde_json::Value,)> = sqlx::query_as(
            "SELECT payload FROM incidents WHERE payload->>'idempotencyKey'=$1 LIMIT 1",
        )
        .bind(key)
        .fetch_optional(&self.pool)
        .await?;
        row.map(|(v,)| serde_json::from_value(v).map_err(|e| ApiError::Storage(e.to_string())))
            .transpose()
    }
    async fn list(&self) -> Result<Vec<Incident>, ApiError> {
        let rows: Vec<(serde_json::Value,)> =
            sqlx::query_as("SELECT payload FROM incidents ORDER BY created_at DESC LIMIT 200")
                .fetch_all(&self.pool)
                .await?;
        rows.into_iter()
            .map(|(v,)| serde_json::from_value(v).map_err(|e| ApiError::Storage(e.to_string())))
            .collect()
    }
    async fn nearby_responders(
        &self,
        point: &GeoPoint,
        limit: usize,
    ) -> Result<Vec<(VerifiedResponder, u32)>, ApiError> {
        let rows: Vec<(Uuid, String, Vec<String>, f64, f64, f64)> = sqlx::query_as("SELECT id,display_name,capabilities,ST_Y(position::geometry),ST_X(position::geometry),ST_Distance(position,ST_SetSRID(ST_MakePoint($1,$2),4326)::geography) FROM responders WHERE available=TRUE AND position IS NOT NULL ORDER BY position <-> ST_SetSRID(ST_MakePoint($1,$2),4326)::geography LIMIT $3").bind(point.longitude).bind(point.latitude).bind(limit as i64).fetch_all(&self.pool).await?;
        Ok(rows
            .into_iter()
            .map(
                |(id, display_name, capabilities, latitude, longitude, distance)| {
                    (
                        VerifiedResponder {
                            id,
                            display_name,
                            capabilities,
                            location: GeoPoint {
                                latitude,
                                longitude,
                                accuracy_meters: 0.0,
                            },
                            available: true,
                        },
                        distance.round() as u32,
                    )
                },
            )
            .collect())
    }
    async fn save_warning(&self, warning: PublicWarning) -> Result<(), ApiError> {
        let payload =
            serde_json::to_value(&warning).map_err(|e| ApiError::Storage(e.to_string()))?;
        sqlx::query("INSERT INTO public_warnings (id,payload,created_at) VALUES ($1,$2,$3)")
            .bind(warning.id)
            .bind(payload)
            .bind(warning.created_at)
            .execute(&self.pool)
            .await?;
        Ok(())
    }
    async fn register_device(&self, device: DeviceRecord) -> Result<(), ApiError> {
        sqlx::query("INSERT INTO device_registrations (id,username,token_hash,created_at,last_seen_at) VALUES ($1,$2,$3,$4,$5)")
            .bind(device.id).bind(device.username).bind(device.token_hash).bind(device.created_at).bind(device.last_seen_at)
            .execute(&self.pool).await?;
        Ok(())
    }
    async fn device(&self, id: Uuid) -> Result<Option<DeviceRecord>, ApiError> {
        let row: Option<(
            Uuid,
            String,
            String,
            chrono::DateTime<chrono::Utc>,
            chrono::DateTime<chrono::Utc>,
        )> = sqlx::query_as(
            "SELECT id,username,token_hash,created_at,last_seen_at FROM device_registrations WHERE id=$1",
        )
        .bind(id)
        .fetch_optional(&self.pool)
        .await?;
        Ok(row.map(
            |(id, username, token_hash, created_at, last_seen_at)| DeviceRecord {
                id,
                username,
                token_hash,
                created_at,
                last_seen_at,
            },
        ))
    }
    async fn device_by_username(&self, username: &str) -> Result<Option<DeviceRecord>, ApiError> {
        let row: Option<(
            Uuid,
            String,
            String,
            chrono::DateTime<chrono::Utc>,
            chrono::DateTime<chrono::Utc>,
        )> = sqlx::query_as(
            "SELECT id,username,token_hash,created_at,last_seen_at FROM device_registrations WHERE username=$1",
        )
        .bind(username)
        .fetch_optional(&self.pool)
        .await?;
        Ok(row.map(
            |(id, username, token_hash, created_at, last_seen_at)| DeviceRecord {
                id,
                username,
                token_hash,
                created_at,
                last_seen_at,
            },
        ))
    }
    async fn touch_device(&self, id: Uuid) -> Result<(), ApiError> {
        sqlx::query("UPDATE device_registrations SET last_seen_at=NOW() WHERE id=$1")
            .bind(id)
            .execute(&self.pool)
            .await?;
        Ok(())
    }
    async fn insert_invite(&self, invite: NetworkInvite) -> Result<NetworkInvite, ApiError> {
        let payload =
            serde_json::to_value(&invite).map_err(|error| ApiError::Storage(error.to_string()))?;
        sqlx::query("INSERT INTO network_invites (id,from_device_id,to_device_id,status,payload,created_at,updated_at) VALUES ($1,$2,$3,$4,$5,$6,$7)")
            .bind(invite.id).bind(invite.from_device_id).bind(invite.to_device_id).bind("pending").bind(payload).bind(invite.created_at).bind(invite.updated_at).execute(&self.pool).await?;
        Ok(invite)
    }
    async fn network_invite(&self, id: Uuid) -> Result<Option<NetworkInvite>, ApiError> {
        let row: Option<(serde_json::Value,)> =
            sqlx::query_as("SELECT payload FROM network_invites WHERE id=$1")
                .bind(id)
                .fetch_optional(&self.pool)
                .await?;
        row.map(|(payload,)| {
            serde_json::from_value(payload).map_err(|error| ApiError::Storage(error.to_string()))
        })
        .transpose()
    }
    async fn update_invite(&self, invite: NetworkInvite) -> Result<NetworkInvite, ApiError> {
        let payload =
            serde_json::to_value(&invite).map_err(|error| ApiError::Storage(error.to_string()))?;
        let status = match invite.status {
            NetworkInviteStatus::Pending => "pending",
            NetworkInviteStatus::Accepted => "accepted",
            NetworkInviteStatus::Declined => "declined",
        };
        sqlx::query("UPDATE network_invites SET status=$2,payload=$3,updated_at=$4 WHERE id=$1")
            .bind(invite.id)
            .bind(status)
            .bind(payload)
            .bind(invite.updated_at)
            .execute(&self.pool)
            .await?;
        Ok(invite)
    }
    async fn incoming_invites(&self, device_id: Uuid) -> Result<Vec<NetworkInvite>, ApiError> {
        let rows: Vec<(serde_json::Value,)> = sqlx::query_as("SELECT payload FROM network_invites WHERE to_device_id=$1 AND status='pending' ORDER BY created_at DESC")
            .bind(device_id).fetch_all(&self.pool).await?;
        rows.into_iter()
            .map(|(payload,)| {
                serde_json::from_value(payload)
                    .map_err(|error| ApiError::Storage(error.to_string()))
            })
            .collect()
    }
    async fn accepted_connection(&self, a: Uuid, b: Uuid) -> Result<bool, ApiError> {
        let row: (bool,) = sqlx::query_as("SELECT EXISTS(SELECT 1 FROM network_invites WHERE status='accepted' AND ((from_device_id=$1 AND to_device_id=$2) OR (from_device_id=$2 AND to_device_id=$1)))")
            .bind(a).bind(b).fetch_one(&self.pool).await?;
        Ok(row.0)
    }
    async fn connections(&self, device_id: Uuid) -> Result<Vec<String>, ApiError> {
        let rows: Vec<(String,)> = sqlx::query_as("SELECT DISTINCT CASE WHEN invite.from_device_id=$1 THEN recipient.username ELSE sender.username END FROM network_invites invite JOIN device_registrations sender ON sender.id=invite.from_device_id JOIN device_registrations recipient ON recipient.id=invite.to_device_id WHERE invite.status='accepted' AND (invite.from_device_id=$1 OR invite.to_device_id=$1) ORDER BY 1")
            .bind(device_id).fetch_all(&self.pool).await?;
        Ok(rows.into_iter().map(|(username,)| username).collect())
    }
    async fn insert_alert(&self, alert: InAppAlert) -> Result<InAppAlert, ApiError> {
        let payload =
            serde_json::to_value(&alert).map_err(|error| ApiError::Storage(error.to_string()))?;
        sqlx::query("INSERT INTO in_app_alerts (id,recipient_device_id,payload,created_at) VALUES ($1,$2,$3,$4)")
            .bind(alert.id).bind(alert.recipient_device_id).bind(payload).bind(alert.created_at).execute(&self.pool).await?;
        Ok(alert)
    }
    async fn alerts(&self, device_id: Uuid) -> Result<Vec<InAppAlert>, ApiError> {
        let rows: Vec<(serde_json::Value,)> = sqlx::query_as("SELECT payload FROM in_app_alerts WHERE recipient_device_id=$1 ORDER BY created_at DESC LIMIT 50")
            .bind(device_id).fetch_all(&self.pool).await?;
        rows.into_iter()
            .map(|(payload,)| {
                serde_json::from_value(payload)
                    .map_err(|error| ApiError::Storage(error.to_string()))
            })
            .collect()
    }
    async fn save_vault(
        &self,
        device_id: Uuid,
        vault: EmergencyVault,
    ) -> Result<EmergencyVault, ApiError> {
        let payload = serde_json::to_value(&vault).map_err(|e| ApiError::Storage(e.to_string()))?;
        sqlx::query("INSERT INTO emergency_vaults (device_id,payload,updated_at) VALUES ($1,$2,$3) ON CONFLICT (device_id) DO UPDATE SET payload=EXCLUDED.payload,updated_at=EXCLUDED.updated_at")
            .bind(device_id).bind(payload).bind(vault.updated_at).execute(&self.pool).await?;
        Ok(vault)
    }
    async fn vault(&self, device_id: Uuid) -> Result<Option<EmergencyVault>, ApiError> {
        let row: Option<(serde_json::Value,)> =
            sqlx::query_as("SELECT payload FROM emergency_vaults WHERE device_id=$1")
                .bind(device_id)
                .fetch_optional(&self.pool)
                .await?;
        row.map(|(payload,)| {
            serde_json::from_value(payload).map_err(|e| ApiError::Storage(e.to_string()))
        })
        .transpose()
    }
    async fn insert_sos_session(&self, session: SosSession) -> Result<SosSession, ApiError> {
        self.update_sos_session(session).await
    }
    async fn update_sos_session(&self, session: SosSession) -> Result<SosSession, ApiError> {
        let payload =
            serde_json::to_value(&session).map_err(|e| ApiError::Storage(e.to_string()))?;
        sqlx::query("INSERT INTO sos_sessions (id,device_id,status,payload,created_at,updated_at) VALUES ($1,$2,$3,$4,$5,$6) ON CONFLICT (id) DO UPDATE SET status=EXCLUDED.status,payload=EXCLUDED.payload,updated_at=EXCLUDED.updated_at")
            .bind(session.id).bind(session.device_id).bind(format!("{:?}", session.status).to_lowercase()).bind(payload).bind(session.created_at).bind(session.updated_at).execute(&self.pool).await?;
        Ok(session)
    }
    async fn sos_session(&self, id: Uuid) -> Result<Option<SosSession>, ApiError> {
        let row: Option<(serde_json::Value,)> =
            sqlx::query_as("SELECT payload FROM sos_sessions WHERE id=$1")
                .bind(id)
                .fetch_optional(&self.pool)
                .await?;
        row.map(|(payload,)| {
            serde_json::from_value(payload).map_err(|e| ApiError::Storage(e.to_string()))
        })
        .transpose()
    }
    async fn pending_sos_sessions(&self) -> Result<Vec<SosSession>, ApiError> {
        let rows: Vec<(serde_json::Value,)> = sqlx::query_as("SELECT payload FROM sos_sessions WHERE status='pendingconfirmation' ORDER BY created_at")
            .fetch_all(&self.pool).await?;
        rows.into_iter()
            .map(|(payload,)| {
                serde_json::from_value(payload).map_err(|e| ApiError::Storage(e.to_string()))
            })
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn haversine_is_symmetric() {
        let a = GeoPoint {
            latitude: 52.37,
            longitude: 4.89,
            accuracy_meters: 1.0,
        };
        let b = GeoPoint {
            latitude: 52.38,
            longitude: 4.90,
            accuracy_meters: 1.0,
        };
        assert_eq!(distance_meters(&a, &b), distance_meters(&b, &a));
        assert!(distance_meters(&a, &b) > 1_000);
    }
}
