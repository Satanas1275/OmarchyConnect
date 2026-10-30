//! omarchy-connect — daemon de bureau pour OmarchyConnect.
//!
//! Lance le serveur (WebSocket + upload), affiche le code d'appairage à donner
//! au téléphone, et charge les commandes jouables depuis le téléphone.
//!
//! ```text
//! omarchy-connect --bind 127.0.0.1 --port 8765
//! ```

mod bt;
mod config;
mod desktop;
mod protocol;
mod relay;
mod server;

use std::path::PathBuf;
use std::sync::Arc;

use anyhow::{Context, Result};
use clap::Parser;
use rand::Rng;
use tracing::info;
use tracing_subscriber::EnvFilter;

use server::AppState;

#[derive(Parser, Debug)]
#[command(name = "omarchy-connect", version, about = "OmarchyConnect desktop daemon")]
struct Args {
    /// Adresse d'écoute (127.0.0.1 derrière un reverse proxy TLS recommandé)
    #[arg(long, default_value = "127.0.0.1")]
    bind: String,

    #[arg(long, default_value_t = 8765)]
    port: u16,

    /// Code d'appairage (généré aléatoirement si absent)
    #[arg(long)]
    code: Option<String>,

    /// Chemin vers commands.toml
    #[arg(long)]
    config: Option<String>,

    /// Dossier de réception des fichiers envoyés par le téléphone
    #[arg(long)]
    upload_dir: Option<PathBuf>,

    /// URL du relais central (public par défaut ; `http://127.0.0.1:6716`
    /// pour le développement local).
    #[arg(long, default_value = "https://omarchy-connect.rocknite-studio.com")]
    relay: String,

    /// Désactive le client de relais (mode direct LAN uniquement)
    #[arg(long)]
    no_relay: bool,

    /// Désactive le transport Bluetooth (profil SPP / RFCOMM)
    #[arg(long)]
    no_bt: bool,
}

fn generate_code() -> String {
    // Alphabet sans caractères ambigus (0/O, 1/I/L)
    const ALPHABET: &[u8] = b"ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    let mut rng = rand::thread_rng();
    (0..8)
        .map(|_| ALPHABET[rng.gen_range(0..ALPHABET.len())] as char)
        .collect()
}

/// Empreinte stable du PC (annoncée dans `hello.fp` et `bt.accept`), lisible
/// par le téléphone pour repérer un changement d'appareil.
fn identity(state_dir: &PathBuf) -> Result<String> {
    let path = state_dir.join("identity");
    if let Ok(id) = std::fs::read_to_string(&path) {
        let id = id.trim().to_string();
        if !id.is_empty() {
            return Ok(id);
        }
    }
    std::fs::create_dir_all(state_dir)?;
    let mut rng = rand::thread_rng();
    let id: String = (0..32)
        .map(|_| format!("{:02x}", rng.gen::<u8>()))
        .collect::<String>()
        .chars()
        .take(48)
        .collect();
    std::fs::write(&path, &id)?;
    Ok(id)
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();

    let args = Args::parse();

    let commands = config::load(args.config.as_deref()).context("chargement des commandes")?;
    let code = args.code.clone().unwrap_or_else(generate_code);

    let config_dir = dirs_config();
    let fingerprint = identity(&config_dir)?;
    let upload_dir = args.upload_dir.clone().unwrap_or_else(default_downloads);

    let state = Arc::new(AppState::new(
        commands,
        code.clone(),
        fingerprint,
        upload_dir.clone(),
    ));

    let addr = format!("{}:{}", args.bind, args.port);
    let listener = tokio::net::TcpListener::bind(&addr)
        .await
        .with_context(|| format!("bind sur {addr}"))?;

    // Client du relais central (appairage + transport internet en E2E).
    if !args.no_relay {
        let relay_state = state.clone();
        let relay_url = args.relay.clone();
        let relay_dir = config_dir.clone();
        tokio::spawn(async move {
            relay::run(relay_state, relay_url, relay_dir).await;
        });
    }

    // Transport Bluetooth classique (SPP/RFCOMM), sans compte ni serveur.
    if !args.no_bt {
        let bt_state = state.clone();
        let bt_dir = config_dir.clone();
        tokio::spawn(async move {
            bt::run(bt_state, bt_dir).await;
        });
    }

    // Presse-papiers PC -> téléphone en temps réel.
    let clipboard_state = state.clone();
    tokio::spawn(async move {
        desktop::clipboard_watch(clipboard_state).await;
    });

    println!("┌───────────────────────────────────────────────┐");
    println!("│  OmarchyConnect — omarchy desktop daemon     │");
    println!("├───────────────────────────────────────────────┤");
    println!("│  listen    ws://{:<26} │", addr);
    if !args.no_relay {
        println!("│  relais    {:<36} │", truncate(&args.relay, 36));
    }
    if args.no_relay {
        println!("│  pair code {:^34} │", code);
    } else {
        println!("│  lan code  {:^34} │", code);
        println!("│  (l'appairage relais affiche son code plus bas) │");
    }
    println!("│  bluetooth {:<34} │", if args.no_bt { "désactivé" } else { "SPP / RFCOMM (canal 3)" });
    println!("│  files     {:<34} │", truncate(&upload_dir.display().to_string(), 34));
    println!("│  commands  {:<34} │", state.commands.len());
    println!("└───────────────────────────────────────────────┘");
    info!(commands = state.commands.len(), "démarrage");
    info!("le code expire à la première utilisation (usage unique)");

    axum::serve(listener, server::router(state))
        .await
        .context("serveur HTTP/WS")?;
    Ok(())
}

fn dirs_config() -> PathBuf {
    std::env::var("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .or_else(|_| {
            std::env::var("HOME").map(|h| PathBuf::from(h).join(".config"))
        })
        .map(|d| d.join("omarchy-connect"))
        .unwrap_or_else(|_| PathBuf::from("."))
}

fn default_downloads() -> PathBuf {
    std::env::var("HOME")
        .map(|h| PathBuf::from(h).join("Downloads"))
        .unwrap_or_else(|_| PathBuf::from("."))
}

fn truncate(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        s.to_string()
    } else {
        let cut: String = s.chars().take(max.saturating_sub(1)).collect();
        format!("{cut}…")
    }
}
