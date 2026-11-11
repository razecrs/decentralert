pub mod api;
pub mod dispatch;
pub mod error;
pub mod models;
pub mod repository;

use std::{sync::Arc, time::Duration};

use axum::Router;
use dispatch::{DispatchAdapter, SimulatedDispatchAdapter};
use models::EmergencyService;
use repository::IncidentRepository;
use tokio::sync::broadcast;

#[derive(Clone)]
pub struct AppState {
    pub repository: Arc<dyn IncidentRepository>,
    pub adapters: Arc<Vec<Arc<dyn DispatchAdapter>>>,
    pub events: broadcast::Sender<models::DomainEvent>,
    pub operator_token: Arc<String>,
}

impl AppState {
    pub fn development(repository: Arc<dyn IncidentRepository>) -> Self {
        let adapters: Vec<Arc<dyn DispatchAdapter>> = EmergencyService::ALL
            .into_iter()
            .map(|service| {
                Arc::new(SimulatedDispatchAdapter::new(
                    service,
                    Duration::from_millis(80),
                )) as Arc<dyn DispatchAdapter>
            })
            .collect();
        let (events, _) = broadcast::channel(256);
        Self {
            repository,
            adapters: Arc::new(adapters),
            events,
            operator_token: Arc::new(
                std::env::var("OPERATOR_TOKEN").unwrap_or_else(|_| "demo-operator".into()),
            ),
        }
    }

    pub fn emit(&self, event: models::DomainEvent) {
        let _ = self.events.send(event);
    }
}

pub fn app(state: AppState) -> Router {
    api::router(state)
}
