use std::{collections::HashMap, sync::RwLock};

use async_trait::async_trait;
use sqlx::{PgPool, postgres::PgPoolOptions};
use uuid::Uuid;

use crate::{
    error::ApiError,
    models::{GeoPoint, Incident, PublicWarning, VerifiedResponder},
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
}

#[derive(Default)]
pub struct MemoryRepository {
    incidents: RwLock<HashMap<Uuid, Incident>>,
    responders: RwLock<Vec<VerifiedResponder>>,
    warnings: RwLock<Vec<PublicWarning>>,
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
