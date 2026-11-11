use std::time::Duration;

use async_trait::async_trait;
use chrono::Utc;

use crate::{
    error::ApiError,
    models::{DispatchAttempt, DispatchStatus, EmergencyService, Incident},
};

#[async_trait]
pub trait DispatchAdapter: Send + Sync {
    fn service(&self) -> EmergencyService;
    fn name(&self) -> &'static str;
    async fn dispatch(&self, incident: &Incident) -> Result<DispatchAttempt, ApiError>;
}

pub struct SimulatedDispatchAdapter {
    service: EmergencyService,
    delay: Duration,
}

impl SimulatedDispatchAdapter {
    pub fn new(service: EmergencyService, delay: Duration) -> Self {
        Self { service, delay }
    }
}

#[async_trait]
impl DispatchAdapter for SimulatedDispatchAdapter {
    fn service(&self) -> EmergencyService {
        self.service
    }
    fn name(&self) -> &'static str {
        "simulated-authority-adapter"
    }
    async fn dispatch(&self, incident: &Incident) -> Result<DispatchAttempt, ApiError> {
        let attempted_at = Utc::now();
        tokio::time::sleep(self.delay).await;
        Ok(DispatchAttempt {
            service: self.service,
            adapter: self.name().into(),
            status: DispatchStatus::Acknowledged,
            attempted_at,
            acknowledged_at: Some(Utc::now()),
            reference: Some(format!(
                "SIM-{}-{}",
                incident.id.simple(),
                format!("{:?}", self.service).to_uppercase()
            )),
            error: None,
        })
    }
}
