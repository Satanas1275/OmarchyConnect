//! Transport Bluetooth classique (RFCOMM / SPP) pour OmarchyConnect.
//!
//! Le PC enregistre le profil SPP (`00001101-…`) auprès de BlueZ. Le téléphone
//! se connecte à l'appareil **déjà appairé** dans les réglages Android, puis :
//!
//!  1. `bt.hello` en clair (device_id + clés publiques EC P-256) ;
//!  2. ECDH + HKDF → clé de session K (jamais transmise) ;
//!  3. `bt.accept` en clair, puis **toutes** les frames applicatives passent
//!     chiffrées dans `{"t":"relay","payload":"<AES-256-GCM>"}`.
//!
//! Ce mode fonctionne sans compte et sans serveur (« Continue without an
//! account »), le lien RFCOMM étant déjà chiffré par l'appairage Bluetooth.

use std::sync::Arc;
use std::time::Duration;

use anyhow::{anyhow, bail, Context, Result};
use bluer::agent::Agent;
use bluer::rfcomm::{Profile, Role};
use bluer::Uuid;
use futures_util::StreamExt;
use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tracing::{info, warn};

use crate::protocol::Incoming;
use crate::relay::{decrypt, encrypt, Identity};
use crate::server::{dispatch, AppState};

/// UUID du profil Serial Port (SPP) — identique côté Android.
const SPP_UUID: u128 = 0x0000_1101_0000_1000_8000_0080_5f9b_34fb;
/// Canal RFCOMM exposé dans le registre SDP.
const SPP_CHANNEL: u16 = 3;

/// Boucle de service : (ré)enregistre le profil SPP et accepte les connexions.
pub async fn run(state: Arc<AppState>, config_dir: std::path::PathBuf) {
    let mut backoff = 2u64;
    loop {
        match serve(&state, &config_dir).await {
            Ok(()) => backoff = 2,
            Err(e) => warn!("bluetooth indisponible : {e:#}"),
        }
        tokio::time::sleep(Duration::from_secs(backoff)).await;
        backoff = (backoff * 2).min(60);
    }
}

async fn serve(state: &Arc<AppState>, config_dir: &std::path::Path) -> Result<()> {
    let ident = Arc::new(Identity::load_or_create(config_dir)?);

    let session = bluer::Session::new()
        .await
        .context("session BlueZ (bluetoothd est-il démarré ?)")?;
    let adapter = session
        .default_adapter()
        .await
        .context("aucun adaptateur Bluetooth")?;
    if !adapter.is_powered().await.unwrap_or(false) {
        adapter
            .set_powered(true)
            .await
            .context("activation de l'adaptateur Bluetooth")?;
    }
    if !adapter.is_powered().await.unwrap_or(false) {
        bail!("adaptateur Bluetooth désactivé (rfkill unblock bluetooth)");
    }
    adapter.set_pairable(true).await.context("set_pairable")?;

    // Agent NoInputNoOutput : accepte l'appairage « just works ».
    let _agent = session
        .register_agent(Agent {
            request_default: true,
            ..Default::default()
        })
        .await
        .context("enregistrement de l'agent d'appairage")?;

    let profile = Profile {
        uuid: Uuid::from_u128(SPP_UUID),
        name: Some("OmarchyConnect".to_string()),
        role: Some(Role::Server),
        channel: Some(SPP_CHANNEL),
        require_authentication: Some(true),
        require_authorization: Some(false),
        auto_connect: Some(false),
        version: Some(0x0102),
        ..Default::default()
    };

    let handle = session
        .register_profile(profile)
        .await
        .with_context(|| format!("enregistrement du profil SPP (canal {SPP_CHANNEL})"))?;
    let mut requests = Box::pin(handle);
    info!(adapter = %adapter.name(), channel = SPP_CHANNEL, "profil SPP enregistré");

    while let Some(request) = requests.next().await {
        let device = request.device();
        match request.accept() {
            Ok(stream) => {
                info!(%device, "connexion Bluetooth acceptée");
                let st = state.clone();
                let id = ident.clone();
                tokio::spawn(async move {
                    if let Err(e) = handle_connection(stream, st, id).await {
                        warn!(%device, "session Bluetooth terminée : {e:#}");
                    }
                });
            }
            Err(e) => warn!(%device, "connexion Bluetooth refusée : {e}"),
        }
    }

    bail!("profil SPP retiré")
}

async fn handle_connection(
    stream: bluer::rfcomm::Stream,
    state: Arc<AppState>,
    ident: Arc<Identity>,
) -> Result<()> {
    let (read, mut write) = tokio::io::split(stream);
    let mut reader = BufReader::new(read);

    // 1. Poignée de main en clair (le lien RFCOMM est déjà chiffré par SSP).
    let mut line = String::new();
    tokio::time::timeout(Duration::from_secs(20), reader.read_line(&mut line))
        .await
        .map_err(|_| anyhow!("délai de poignée de main dépassé"))?
        .context("lecture bt.hello")?;
    let hello: Value = serde_json::from_str(line.trim()).context("bt.hello illisible")?;
    if hello["t"] != "bt.hello" {
        write_line(
            &mut write,
            &json!({ "t": "error", "error": "bt.hello attendu" }).to_string(),
        )
        .await?;
        bail!("première trame inattendue : {}", hello["t"]);
    }

    let peer = hello["device_id"].as_str().unwrap_or_default().to_string();
    if peer.is_empty() {
        bail!("bt.hello sans device_id");
    }
    let dh = hello["dh_pubkey"].as_str().unwrap_or_default();
    if dh.is_empty() {
        bail!("bt.hello sans clé DH");
    }

    // 2. ECDH : la clé privée du PC ne quitte jamais la machine.
    let key = ident
        .derive_session_key(dh)
        .context("dérivation de la clé de session")?;

    let accept = json!({
        "t": "bt.accept",
        "pc": state.pc_name,
        "pc_id": ident.device_id,
        "pc_dh_pubkey": ident.pubkey_b64()?,
        "fp": state.fingerprint,
    });
    write_line(&mut write, &accept.to_string()).await?;
    info!(phone = %hello["name"].as_str().unwrap_or("?"), %peer, "lien Bluetooth E2E établi");

    // 3. Frames applicatives chiffrées, comme via le relais.
    let mut pushes = state.register_sink();
    loop {
        let mut line = String::new();
        let mut authed = true; // l'appairage Bluetooth fait foi
        tokio::select! {
            read = reader.read_line(&mut line) => {
                let n = read.context("lecture Bluetooth")?;
                if n == 0 {
                    break;
                }
                let text = line.trim();
                if text.is_empty() {
                    continue;
                }
                let Ok(v) = serde_json::from_str::<Value>(text) else {
                    warn!("trame Bluetooth illisible");
                    continue;
                };
                match v["t"].as_str().unwrap_or_default() {
                    "ping" => write_line(&mut write, &json!({ "t": "pong" }).to_string()).await?,
                    "relay" => {
                        let Some(payload) = v["payload"].as_str() else { continue };
                        let Ok(inner) = decrypt(&key, payload) else {
                            warn!("payload Bluetooth indéchiffrable");
                            continue;
                        };
                        let Some(incoming) = Incoming::parse(&inner) else {
                            warn!("frame applicative Bluetooth illisible");
                            continue;
                        };
                        if let Some(reply) = dispatch(&state, incoming, &mut authed).await {
                            let enc = encrypt(&key, &reply)?;
                            let frame = json!({ "t": "relay", "payload": enc }).to_string();
                            write_line(&mut write, &frame).await?;
                        }
                    }
                    "unpair" => break,
                    other => tracing::debug!(t = other, "trame Bluetooth ignorée"),
                }
            }
            pushed = pushes.recv() => {
                let Some(plain) = pushed else { bail!("canal de diffusion fermé") };
                let enc = encrypt(&key, &plain)?;
                let frame = json!({ "t": "relay", "payload": enc }).to_string();
                write_line(&mut write, &frame).await?;
            }
        }
    }

    info!(%peer, "lien Bluetooth fermé");
    Ok(())
}

async fn write_line<W>(writer: &mut W, text: &str) -> Result<()>
where
    W: tokio::io::AsyncWrite + Unpin,
{
    writer.write_all(text.as_bytes()).await?;
    writer.write_all(b"\n").await?;
    writer.flush().await?;
    Ok(())
}
