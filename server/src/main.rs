//! Relais OmarchyConnect — point de rendez-vous PC ↔ téléphone.
//!
//! * `127.0.0.1:6716` en local, publié sur `https://omarchy-connect.rocknite-studio.com/`
//!   via un reverse proxy (TLS) ;
//! * le serveur ne stocke que des **clés publiques** et des paires (« qui se
//!   connecte à qui ») : aucune clé privée, aucun payload lisible (chiffrement E2E) ;
//! * les requêtes sensibles sont signées ECDSA + nonce à usage unique.

mod routes;
mod state;

use std::path::PathBuf;
use std::sync::Arc;

use anyhow::Result;
use clap::Parser;

#[derive(Parser)]
#[command(name = "omarchy-connect-server", about = "Relais OmarchyConnect")]
struct Args {
    /// Adresse d'écoute (127.0.0.1 derrière le reverse proxy du domaine).
    #[arg(long, default_value = "127.0.0.1")]
    bind: String,

    /// Port d'écoute (6716 en local, comme convenu).
    #[arg(long, default_value_t = 6716)]
    port: u16,

    /// Fichier d'état (appareils appairés, persistés).
    #[arg(long)]
    state: Option<PathBuf>,
}

fn default_state_path() -> PathBuf {
    if let Ok(xdg) = std::env::var("XDG_DATA_HOME") {
        return PathBuf::from(xdg).join("omarchy-connect-server/state.json");
    }
    if let Ok(home) = std::env::var("HOME") {
        return PathBuf::from(home)
            .join(".local/share/omarchy-connect-server/state.json");
    }
    PathBuf::from("data/state.json")
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            std::env::var("RUST_LOG").unwrap_or_else(|_| "omarchy_connect_server=info,info".into()),
        )
        .init();

    let args = Args::parse();
    let state_path = args.state.clone().unwrap_or_else(default_state_path);

    let registry = state::Registry::load(state_path.clone())?;
    let app = Arc::new(routes::App {
        registry,
        peers: Default::default(),
    });

    let addr = format!("{}:{}", args.bind, args.port);
    let listener = tokio::net::TcpListener::bind(&addr).await?;

    println!("┌──────────────────────────────────────────────┐");
    println!("│  OmarchyConnect — relais central            │");
    println!("│  écoute : http://{:<28}│", addr);
    println!("│  état   : {:<28}│", state_path.display().to_string());
    println!("│  domaine: https://omarchy-connect.rocknite- │");
    println!("│          studio.com/ (reverse proxy + TLS)  │");
    println!("└──────────────────────────────────────────────┘");

    axum::serve(listener, routes::router(app)).await?;
    Ok(())
}
