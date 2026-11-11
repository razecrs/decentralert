use std::{convert::Infallible, time::Duration};

use axum::{
    Json, Router,
    extract::{Path, State},
    http::{HeaderMap, StatusCode},
    response::sse::{Event, KeepAlive, Sse},
    routing::{get, post},
};
use chrono::Utc;
use futures::{Stream, future::join_all};
use serde::Deserialize;
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
    Ok(Json(state.repository.list().await?))
}
async fn get_incident(
    State(state): State<AppState>,
    Path(id): Path<Uuid>,
) -> Result<Json<Incident>, ApiError> {
    state
        .repository
        .get(id)
        .await?
        .map(Json)
        .ok_or(ApiError::NotFound)
}

async fn create_incident(
    State(state): State<AppState>,
    Json(request): Json<CreateIncidentRequest>,
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
    use std::sync::Arc;
    use tower::ServiceExt;

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
}
