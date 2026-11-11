use std::{net::SocketAddr, sync::Arc};

use decentralert_backend::{
    AppState, app,
    repository::{IncidentRepository, MemoryRepository, PostgresRepository},
};
use tower_http::{
    request_id::{MakeRequestUuid, PropagateRequestIdLayer, SetRequestIdLayer},
    trace::TraceLayer,
};
use tracing::info;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "decentralert_backend=debug,tower_http=info".into()),
        )
        .init();

    let repository: Arc<dyn IncidentRepository> = match std::env::var("DATABASE_URL") {
        Ok(url) => {
            info!("connecting to PostgreSQL");
            Arc::new(PostgresRepository::connect(&url).await?)
        }
        Err(_) => {
            info!("DATABASE_URL not set; using in-memory development repository");
            Arc::new(MemoryRepository::seeded())
        }
    };

    let state = AppState::development(repository);
    let app = app(state)
        .layer(PropagateRequestIdLayer::x_request_id())
        .layer(SetRequestIdLayer::new(
            http::HeaderName::from_static("x-request-id"),
            MakeRequestUuid,
        ))
        .layer(TraceLayer::new_for_http());

    let address: SocketAddr = std::env::var("DECENTRALERT_BIND")
        .unwrap_or_else(|_| "0.0.0.0:8080".into())
        .parse()?;
    let listener = tokio::net::TcpListener::bind(address).await?;
    info!(%address, "Decentralert API listening");
    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await?;
    Ok(())
}

async fn shutdown_signal() {
    let ctrl_c = async {
        tokio::signal::ctrl_c()
            .await
            .expect("install Ctrl+C handler")
    };
    #[cfg(unix)]
    let terminate = async {
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("install SIGTERM handler")
            .recv()
            .await;
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();
    tokio::select! { _ = ctrl_c => {}, _ = terminate => {} }
}
