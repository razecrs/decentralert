use std::{convert::Infallible, time::Duration};

use axum::{
    Json, Router,
    extract::{Path, State},
    http::{HeaderMap, StatusCode},
    response::sse::{Event, KeepAlive, Sse},
    routing::{get, post},
};
use chrono::{Duration as ChronoDuration, Utc};
use futures::{Stream, future::join_all};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use tokio_stream::{StreamExt, wrappers::BroadcastStream};
use tower_http::cors::{Any, CorsLayer};
use uuid::Uuid;

use crate::{AppState, error::ApiError, models::*};

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/health", get(health))
        .route("/v1/incidents", get(list_incidents).post(create_incident))
        .route("/v1/incidents/{id}", get(get_incident))
        .route("/v1/incidents/{id}/cancel", post(cancel_incident))
        .route("/v1/incidents/{id}/close", post(close_incident))
        .route(
            "/v1/incidents/{id}/responders/{responder_id}/accept",
            post(accept_responder),
        )
        .route("/v1/events", get(events))
        .route("/v1/public-warnings", post(create_public_warning))
        .route("/v1/devices/register", post(register_device))
        .route("/v1/vault", get(get_vault).put(put_vault))
        .route("/v1/sos/sessions", post(create_sos_session))
        .route("/v1/sos/sessions/{id}", get(get_sos_session))
        .route(
            "/v1/sos/sessions/{id}/heartbeat",
            post(heartbeat_sos_session),
        )
        .route("/v1/sos/sessions/{id}/confirm", post(confirm_sos_session))
        .route("/v1/sos/sessions/{id}/cancel", post(cancel_sos_session))
        .layer(
            CorsLayer::new()
                .allow_origin(Any)
                .allow_methods(Any)
                .allow_headers(Any),
        )
        .with_state(state)
}

async fn health() -> Json<serde_json::Value> {
    Json(
        serde_json::json!({ "status": "ok", "service": "decentralert-api", "realEmergencyDispatch": false }),
    )
}

async fn list_incidents(State(state): State<AppState>) -> Result<Json<Vec<Incident>>, ApiError> {
    Ok(Json(
        state
            .repository
            .list()
            .await?
            .into_iter()
            .map(Incident::public_view)
            .collect(),
    ))
}
async fn get_incident(
    State(state): State<AppState>,
    Path(id): Path<Uuid>,
) -> Result<Json<Incident>, ApiError> {
    state
        .repository
        .get(id)
        .await?
        .map(Incident::public_view)
        .map(Json)
        .ok_or(ApiError::NotFound)
}

async fn create_incident(
    State(state): State<AppState>,
    Json(request): Json<CreateIncidentRequest>,
) -> Result<(StatusCode, Json<Incident>), ApiError> {
    dispatch_incident(&state, request, false, None, 0, false).await
}

async fn dispatch_incident(
    state: &AppState,
    request: CreateIncidentRequest,
    private_incident: bool,
    source_session_id: Option<Uuid>,
    emergency_circle_notified: usize,
    offline_escalation: bool,
) -> Result<(StatusCode, Json<Incident>), ApiError> {
    request
        .validate()
        .map_err(|e| ApiError::BadRequest(e.into()))?;
    if let Some(existing) = state
        .repository
        .by_idempotency_key(&request.idempotency_key)
        .await?
    {
        return Ok((StatusCode::OK, Json(existing)));
    }
    let now = Utc::now();
    let nearby = state
        .repository
        .nearby_responders(&request.location, 4)
        .await?;
    let offers = nearby
        .into_iter()
        .map(|(r, distance)| ResponderOffer {
            responder_id: r.id,
            display_name: r.display_name,
            distance_meters: distance,
            estimated_arrival_seconds: (distance as f32 / 1.4).round() as u32,
            status: OfferStatus::Offered,
        })
        .collect();
    let mut incident = Incident {
        id: Uuid::new_v4(),
        idempotency_key: request.idempotency_key,
        category: request.category,
        status: IncidentStatus::Dispatching,
        location: request.location,
        description: request.description,
        profile: request.profile,
        evidence: request.evidence,
        sensor_context: request.sensor_context,
        dispatch_attempts: vec![],
        responder_offers: offers,
        source_session_id,
        emergency_circle_notified,
        offline_escalation,
        private_incident,
        created_at: now,
        updated_at: now,
    };
    state.repository.insert(incident.clone()).await?;
    state.emit(DomainEvent::incident("incident.created", &incident));

    let required = incident.category.services();
    let futures = state
        .adapters
        .iter()
        .filter(|a| required.contains(&a.service()))
        .map(|a| a.dispatch(&incident));
    incident.dispatch_attempts = join_all(futures)
        .await
        .into_iter()
        .map(|r| {
            r.unwrap_or_else(|e| DispatchAttempt {
                service: EmergencyService::Rescue,
                adapter: "unknown".into(),
                status: DispatchStatus::Failed,
                attempted_at: Utc::now(),
                acknowledged_at: None,
                reference: None,
                error: Some(e.to_string()),
            })
        })
        .collect();
    incident.status = if incident
        .dispatch_attempts
        .iter()
        .any(|a| a.status == DispatchStatus::Acknowledged)
    {
        IncidentStatus::Acknowledged
    } else {
        IncidentStatus::Dispatching
    };
    incident.updated_at = Utc::now();
    state.repository.update(incident.clone()).await?;
    state.emit(DomainEvent::incident("dispatch.completed", &incident));
    Ok((StatusCode::CREATED, Json(incident)))
}

async fn register_device(
    State(state): State<AppState>,
    Json(_request): Json<DeviceRegistrationRequest>,
) -> Result<(StatusCode, Json<DeviceRegistration>), ApiError> {
    let now = Utc::now();
    let device_id = Uuid::new_v4();
    let device_token = format!("{}{}", Uuid::new_v4().simple(), Uuid::new_v4().simple());
    state
        .repository
        .register_device(DeviceRecord {
            id: device_id,
            token_hash: hash_token(&device_token),
            created_at: now,
            last_seen_at: now,
        })
        .await?;
    Ok((
        StatusCode::CREATED,
        Json(DeviceRegistration {
            device_id,
            device_token,
        }),
    ))
}

async fn get_vault(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<EmergencyVault>, ApiError> {
    let device_id = authorize_device(&state, &headers).await?;
    state
        .repository
        .vault(device_id)
        .await?
        .map(Json)
        .ok_or(ApiError::NotFound)
}

async fn put_vault(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(mut vault): Json<EmergencyVault>,
) -> Result<Json<EmergencyVault>, ApiError> {
    let device_id = authorize_device(&state, &headers).await?;
    vault
        .validate()
        .map_err(|error| ApiError::BadRequest(error.into()))?;
    vault.updated_at = Utc::now();
    Ok(Json(state.repository.save_vault(device_id, vault).await?))
}

async fn create_sos_session(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<CreateSosSessionRequest>,
) -> Result<(StatusCode, Json<SosSession>), ApiError> {
    let device_id = authorize_device(&state, &headers).await?;
    request
        .location
        .validate()
        .map_err(|error| ApiError::BadRequest(error.into()))?;
    let vault =
        state.repository.vault(device_id).await?.ok_or_else(|| {
            ApiError::Conflict("save an emergency vault before starting SOS".into())
        })?;
    let now = Utc::now();
    let session = SosSession {
        id: Uuid::new_v4(),
        device_id,
        status: SosSessionStatus::PendingConfirmation,
        category: request.category,
        location: request.location,
        description: request.description,
        sensor_context: request.sensor_context,
        vault_snapshot: Some(vault),
        notification_attempts: vec![],
        incident_id: None,
        confirmation_deadline: now + ChronoDuration::seconds(30),
        last_heartbeat_at: now,
        created_at: now,
        updated_at: now,
    };
    let session = state.repository.insert_sos_session(session).await?;
    emit_safe_session_event(&state, "sos.prealert.created", &session);
    Ok((StatusCode::CREATED, Json(session)))
}

async fn get_sos_session(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<Uuid>,
) -> Result<Json<SosSession>, ApiError> {
    let device_id = authorize_device(&state, &headers).await?;
    Ok(Json(owned_session(&state, id, device_id).await?))
}

async fn heartbeat_sos_session(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<Uuid>,
    Json(request): Json<HeartbeatRequest>,
) -> Result<Json<SosSession>, ApiError> {
    let device_id = authorize_device(&state, &headers).await?;
    let mut session = owned_session(&state, id, device_id).await?;
    if session.status != SosSessionStatus::PendingConfirmation {
        return Ok(Json(session));
    }
    if let Some(location) = request.location {
        location
            .validate()
            .map_err(|error| ApiError::BadRequest(error.into()))?;
        session.location = location;
    }
    if let Some(battery) = request.battery_percent
        && let Some(context) = session.sensor_context.as_mut()
    {
        context.battery_percent = Some(battery);
    }
    session.last_heartbeat_at = Utc::now();
    session.updated_at = Utc::now();
    Ok(Json(state.repository.update_sos_session(session).await?))
}

async fn confirm_sos_session(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<Uuid>,
) -> Result<Json<SosSession>, ApiError> {
    let device_id = authorize_device(&state, &headers).await?;
    let session = owned_session(&state, id, device_id).await?;
    Ok(Json(escalate_sos_session(&state, session, false).await?))
}

async fn cancel_sos_session(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<Uuid>,
) -> Result<Json<SosSession>, ApiError> {
    let device_id = authorize_device(&state, &headers).await?;
    let mut session = owned_session(&state, id, device_id).await?;
    if session.status != SosSessionStatus::PendingConfirmation {
        return Err(ApiError::Conflict("SOS session is already terminal".into()));
    }
    session.status = SosSessionStatus::Cancelled;
    session.vault_snapshot = None;
    session.notification_attempts.clear();
    session.updated_at = Utc::now();
    let session = state.repository.update_sos_session(session).await?;
    emit_safe_session_event(&state, "sos.prealert.cancelled", &session);
    Ok(Json(session))
}

async fn owned_session(
    state: &AppState,
    id: Uuid,
    device_id: Uuid,
) -> Result<SosSession, ApiError> {
    let session = state
        .repository
        .sos_session(id)
        .await?
        .ok_or(ApiError::NotFound)?;
    if session.device_id != device_id {
        return Err(ApiError::Unauthorized);
    }
    Ok(session)
}

async fn escalate_sos_session(
    state: &AppState,
    mut session: SosSession,
    offline: bool,
) -> Result<SosSession, ApiError> {
    if session.status != SosSessionStatus::PendingConfirmation {
        return Ok(session);
    }
    let vault = session
        .vault_snapshot
        .clone()
        .ok_or_else(|| ApiError::Conflict("SOS vault snapshot is unavailable".into()))?;
    let should_notify = vault.escalation.notify_circle_on_confirm || offline;
    session.notification_attempts = if should_notify {
        vault
            .circle
            .iter()
            .filter(|member| member.accepted)
            .map(|member| CircleNotificationAttempt {
                member_id: member.id,
                member_name: member.name.clone(),
                channels: member.channels.clone(),
                status: "simulated_notified".into(),
                attempted_at: Utc::now(),
            })
            .collect()
    } else {
        vec![]
    };
    let profile = if vault.consent.share_medical_profile {
        vault.profile.clone()
    } else {
        EmergencyProfile {
            display_name: vault.profile.display_name.clone(),
            ..EmergencyProfile::default()
        }
    };
    let request = CreateIncidentRequest {
        idempotency_key: format!("sos-session-{}", session.id),
        category: session.category,
        location: session.location.clone(),
        description: session.description.clone(),
        profile,
        evidence: vec![],
        sensor_context: session.sensor_context.clone(),
    };
    let (_, Json(incident)) = dispatch_incident(
        state,
        request,
        true,
        Some(session.id),
        session.notification_attempts.len(),
        offline,
    )
    .await?;
    session.incident_id = Some(incident.id);
    session.status = if offline {
        SosSessionStatus::EscalatedOffline
    } else {
        SosSessionStatus::Confirmed
    };
    session.updated_at = Utc::now();
    let session = state.repository.update_sos_session(session).await?;
    emit_safe_session_event(
        state,
        if offline {
            "sos.offline.escalated"
        } else {
            "sos.confirmed"
        },
        &session,
    );
    Ok(session)
}

async fn authorize_device(state: &AppState, headers: &HeaderMap) -> Result<Uuid, ApiError> {
    let device_id = headers
        .get("x-device-id")
        .and_then(|value| value.to_str().ok())
        .and_then(|value| Uuid::parse_str(value).ok())
        .ok_or(ApiError::Unauthorized)?;
    let token = headers
        .get("authorization")
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "))
        .ok_or(ApiError::Unauthorized)?;
    let device = state
        .repository
        .device(device_id)
        .await?
        .ok_or(ApiError::Unauthorized)?;
    if device.token_hash != hash_token(token) {
        return Err(ApiError::Unauthorized);
    }
    state.repository.touch_device(device_id).await?;
    Ok(device_id)
}

fn hash_token(token: &str) -> String {
    hex::encode(Sha256::digest(token.as_bytes()))
}

fn emit_safe_session_event(state: &AppState, event_type: &str, session: &SosSession) {
    state.emit(DomainEvent {
        event_id: Uuid::new_v4(),
        event_type: event_type.into(),
        incident_id: session.incident_id,
        occurred_at: Utc::now(),
        payload: serde_json::json!({ "sessionId": session.id, "status": session.status, "contactCount": session.notification_attempts.len() }),
    });
}

pub async fn sos_watchdog(state: AppState) {
    let mut interval = tokio::time::interval(Duration::from_secs(2));
    loop {
        interval.tick().await;
        let Ok(sessions) = state.repository.pending_sos_sessions().await else {
            continue;
        };
        for mut session in sessions {
            let now = Utc::now();
            let policy = session
                .vault_snapshot
                .as_ref()
                .map(|vault| vault.escalation.clone())
                .unwrap_or_default();
            if policy.escalate_when_heartbeat_lost
                && now - session.last_heartbeat_at
                    > ChronoDuration::seconds(policy.heartbeat_timeout_seconds as i64)
            {
                let _ = escalate_sos_session(&state, session, true).await;
            } else if now > session.confirmation_deadline + ChronoDuration::minutes(5) {
                session.status = SosSessionStatus::Cancelled;
                session.vault_snapshot = None;
                session.updated_at = now;
                let _ = state.repository.update_sos_session(session).await;
            }
        }
    }
}

async fn cancel_incident(
    State(state): State<AppState>,
    Path(id): Path<Uuid>,
) -> Result<Json<Incident>, ApiError> {
    transition(&state, id, IncidentStatus::Cancelled, "incident.cancelled").await
}
async fn close_incident(
    State(state): State<AppState>,
    Path(id): Path<Uuid>,
    headers: HeaderMap,
) -> Result<Json<Incident>, ApiError> {
    authorize(&state, &headers)?;
    transition(&state, id, IncidentStatus::Closed, "incident.closed").await
}

async fn transition(
    state: &AppState,
    id: Uuid,
    status: IncidentStatus,
    event: &str,
) -> Result<Json<Incident>, ApiError> {
    let mut incident = state.repository.get(id).await?.ok_or(ApiError::NotFound)?;
    if matches!(
        incident.status,
        IncidentStatus::Cancelled | IncidentStatus::Closed
    ) {
        return Err(ApiError::Conflict("incident is already terminal".into()));
    }
    incident.status = status;
    incident.updated_at = Utc::now();
    state.repository.update(incident.clone()).await?;
    state.emit(DomainEvent::incident(event, &incident));
    Ok(Json(incident))
}

async fn accept_responder(
    State(state): State<AppState>,
    Path((id, responder_id)): Path<(Uuid, Uuid)>,
) -> Result<Json<Incident>, ApiError> {
    let mut incident = state.repository.get(id).await?.ok_or(ApiError::NotFound)?;
    if matches!(
        incident.status,
        IncidentStatus::Cancelled | IncidentStatus::Closed
    ) {
        return Err(ApiError::Conflict("incident is no longer active".into()));
    }
    let offer = incident
        .responder_offers
        .iter_mut()
        .find(|o| o.responder_id == responder_id)
        .ok_or(ApiError::NotFound)?;
    offer.status = OfferStatus::Accepted;
    incident.status = IncidentStatus::ResponderEnRoute;
    incident.updated_at = Utc::now();
    state.repository.update(incident.clone()).await?;
    state.emit(DomainEvent::incident("responder.accepted", &incident));
    Ok(Json(incident))
}

async fn create_public_warning(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<PublicWarningRequest>,
) -> Result<(StatusCode, Json<PublicWarning>), ApiError> {
    authorize(&state, &headers)?;
    request
        .center
        .validate()
        .map_err(|e| ApiError::BadRequest(e.into()))?;
    if request.title.trim().is_empty()
        || request.instructions.trim().is_empty()
        || !(100..=100_000).contains(&request.radius_meters)
    {
        return Err(ApiError::BadRequest(
            "title, instructions and radiusMeters between 100 and 100000 are required".into(),
        ));
    }
    let warning = PublicWarning {
        id: Uuid::new_v4(),
        request,
        created_at: Utc::now(),
        issued_by: "operator".into(),
    };
    state.repository.save_warning(warning.clone()).await?;
    state.emit(DomainEvent {
        event_id: Uuid::new_v4(),
        event_type: "public_warning.created".into(),
        incident_id: None,
        occurred_at: Utc::now(),
        payload: serde_json::to_value(&warning).unwrap_or_default(),
    });
    Ok((StatusCode::CREATED, Json(warning)))
}

fn authorize(state: &AppState, headers: &HeaderMap) -> Result<(), ApiError> {
    let token = headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "));
    if token == Some(state.operator_token.as_str()) {
        Ok(())
    } else {
        Err(ApiError::Unauthorized)
    }
}

async fn events(
    State(state): State<AppState>,
) -> Sse<impl Stream<Item = Result<Event, Infallible>>> {
    let stream = BroadcastStream::new(state.events.subscribe())
        .filter_map(|item| item.ok())
        .map(|event| {
            Ok(Event::default()
                .event(event.event_type.clone())
                .id(event.event_id.to_string())
                .json_data(event)
                .unwrap_or_else(|_| Event::default().event("serialization.error")))
        });
    Sse::new(stream).keep_alive(
        KeepAlive::new()
            .interval(Duration::from_secs(15))
            .text("keep-alive"),
    )
}

#[derive(Deserialize)]
#[allow(dead_code)]
struct Pagination {
    limit: Option<usize>,
}

#[cfg(test)]
mod tests {
    use crate::{AppState, app, repository::MemoryRepository};
    use axum::{
        body::{Body, to_bytes},
        http::{Request, StatusCode},
    };
    use chrono::Utc;
    use std::sync::Arc;
    use tower::ServiceExt;
    use uuid::Uuid;

    fn request_json(key: &str) -> String {
        serde_json::json!({ "idempotencyKey": key, "category": "unknown", "location": { "latitude": 52.3728, "longitude": 4.8936, "accuracyMeters": 7.0 }, "description": "Person unresponsive", "profile": { "displayName": "Demo User", "allergies": ["penicillin"], "medications": [], "conditions": [], "accessibilityNeeds": [], "emergencyContacts": [] }, "evidence": [] }).to_string()
    }

    #[tokio::test]
    async fn unknown_incident_fans_out_and_is_idempotent() {
        let state = AppState::development(Arc::new(MemoryRepository::seeded()));
        let router = app(state);
        let response = router
            .clone()
            .oneshot(
                Request::post("/v1/incidents")
                    .header("content-type", "application/json")
                    .body(Body::from(request_json("same-key")))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::CREATED);
        let body = to_bytes(response.into_body(), usize::MAX).await.unwrap();
        let incident: crate::models::Incident = serde_json::from_slice(&body).unwrap();
        assert_eq!(incident.dispatch_attempts.len(), 4);
        assert!(!incident.responder_offers.is_empty());
        let duplicate = router
            .oneshot(
                Request::post("/v1/incidents")
                    .header("content-type", "application/json")
                    .body(Body::from(request_json("same-key")))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(duplicate.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn public_warning_requires_operator() {
        let router = app(AppState::development(Arc::new(MemoryRepository::seeded())));
        let body = serde_json::json!({ "title": "Smoke plume", "instructions": "Go indoors", "category": "fire", "center": { "latitude": 52.37, "longitude": 4.89, "accuracyMeters": 5.0 }, "radiusMeters": 1500 }).to_string();
        let unauthorized = router
            .clone()
            .oneshot(
                Request::post("/v1/public-warnings")
                    .header("content-type", "application/json")
                    .body(Body::from(body.clone()))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(unauthorized.status(), StatusCode::UNAUTHORIZED);
        let authorized = router
            .oneshot(
                Request::post("/v1/public-warnings")
                    .header("content-type", "application/json")
                    .header("authorization", "Bearer demo-operator")
                    .body(Body::from(body))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(authorized.status(), StatusCode::CREATED);
    }

    #[tokio::test]
    async fn emergency_vault_session_confirms_and_redacts_public_incident() {
        let router = app(AppState::development(Arc::new(MemoryRepository::seeded())));
        let registered = router
            .clone()
            .oneshot(
                Request::post("/v1/devices/register")
                    .header("content-type", "application/json")
                    .body(Body::from(r#"{"deviceName":"test"}"#))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(registered.status(), StatusCode::CREATED);
        let body = to_bytes(registered.into_body(), usize::MAX).await.unwrap();
        let registration: crate::models::DeviceRegistration =
            serde_json::from_slice(&body).unwrap();
        let auth = format!("Bearer {}", registration.device_token);
        let vault = serde_json::json!({
            "profile": { "displayName": "Private Citizen", "allergies": ["penicillin"] },
            "circle": [{ "id": Uuid::new_v4(), "name": "Trusted person", "phone": "+31000000000", "channels": ["call", "sms"], "accepted": true, "priority": 1 }],
            "consent": { "sharePreciseLocation": true, "shareMedicalProfile": true, "consentedSnapshots": false, "consentedAudio": false, "consentVersion": "test" },
            "escalation": { "notifyCircleOnConfirm": true, "escalateWhenHeartbeatLost": false, "heartbeatTimeoutSeconds": 15 },
            "updatedAt": Utc::now(),
        }).to_string();
        let saved = router
            .clone()
            .oneshot(
                Request::put("/v1/vault")
                    .header("content-type", "application/json")
                    .header("x-device-id", registration.device_id.to_string())
                    .header("authorization", auth.clone())
                    .body(Body::from(vault))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(saved.status(), StatusCode::OK);
        let session_request = serde_json::json!({ "category": "unknown", "location": { "latitude": 52.37, "longitude": 4.89, "accuracyMeters": 8.0 }, "description": "test SOS" }).to_string();
        let created = router
            .clone()
            .oneshot(
                Request::post("/v1/sos/sessions")
                    .header("content-type", "application/json")
                    .header("x-device-id", registration.device_id.to_string())
                    .header("authorization", auth.clone())
                    .body(Body::from(session_request))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(created.status(), StatusCode::CREATED);
        let body = to_bytes(created.into_body(), usize::MAX).await.unwrap();
        let session: crate::models::SosSession = serde_json::from_slice(&body).unwrap();
        let confirmed = router
            .clone()
            .oneshot(
                Request::post(format!("/v1/sos/sessions/{}/confirm", session.id))
                    .header("content-type", "application/json")
                    .header("x-device-id", registration.device_id.to_string())
                    .header("authorization", auth)
                    .body(Body::from("{}"))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(confirmed.status(), StatusCode::OK);
        let body = to_bytes(confirmed.into_body(), usize::MAX).await.unwrap();
        let confirmed_session: crate::models::SosSession = serde_json::from_slice(&body).unwrap();
        assert_eq!(confirmed_session.notification_attempts.len(), 1);
        let incident_id = confirmed_session.incident_id.unwrap();
        let public = router
            .oneshot(
                Request::get(format!("/v1/incidents/{incident_id}"))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        let body = to_bytes(public.into_body(), usize::MAX).await.unwrap();
        let incident: crate::models::Incident = serde_json::from_slice(&body).unwrap();
        assert_eq!(incident.profile.display_name, "Protected citizen");
        assert_eq!(incident.emergency_circle_notified, 1);
    }
}
