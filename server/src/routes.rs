//! Routes HTTP + WebSocket du relais.
//!
//! Sécurité :
//!  - les requêtes sensibles sont **signées** (ECDSA P-256/SHA-256) avec une clé
//!    privée qui ne quitte jamais l'appareil ; le serveur ne stocke que la publique ;
//!  - chaque requête porte un nonce à usage unique (anti-rejeu), valable 60 s ;
//!  - les frames relayées sont **opaques** : le serveur route `from → to` sans
//!    jamais lire le payload (chiffré AES-256-GCM entre le PC et le téléphone).

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use base64::Engine as _;
use futures::{SinkExt, StreamExt};
use p256::ecdsa::{Signature, VerifyingKey};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use signature::Verifier;
use tokio::sync::mpsc::UnboundedSender;

use crate::state::{Registry, Role};

type Api<T> = Result<Json<T>, (StatusCode, String)>;

fn bad(e: impl std::fmt::Display) -> (StatusCode, String) {
    (StatusCode::BAD_REQUEST, e.to_string())
}

fn decode_b64(s: &str) -> Result<Vec<u8>, (StatusCode, String)> {
    base64::engine::general_purpose::STANDARD
        .decode(s)
        .map_err(|e| bad(format!("base64 invalide : {e}")))
}

/// Vérifie une signature ECDSA (DER, base64) sur `msg` avec une publique SEC1 (base64).
fn verify_sig(pubkey_b64: &str, msg: &str, sig_b64: &str) -> anyhow::Result<()> {
    let pk = base64::engine::general_purpose::STANDARD.decode(pubkey_b64)?;
    let sig_bytes = base64::engine::general_purpose::STANDARD.decode(sig_b64)?;
    let vk = VerifyingKey::from_sec1_bytes(&pk)?;
    let sig = Signature::from_der(&sig_bytes)?;
    vk.verify(msg.as_bytes(), &sig)?;
    Ok(())
}

pub struct App {
    pub registry: Registry,
    /// Canal d'émission vers les sockets WS connectées (par device_id).
    pub peers: Mutex<HashMap<String, UnboundedSender<String>>>,
}

pub fn router(app: Arc<App>) -> Router {
    Router::new()
        .route("/healthz", get(healthz))
        .route("/api/challenge", post(challenge))
        .route("/api/register", post(register))
        .route("/api/pair/announce", post(announce))
        .route("/api/pair/join", post(join))
        .route("/api/pair/finalize", post(finalize))
        .route("/api/pair/status", get(status))
        .route("/api/pair/unpair", post(unpair))
        .route("/ws", get(ws_handler))
        .with_state(app)
}

// ------------------------------------------------------------------ helpers

fn push(app: &App, device: &str, v: Value) {
    if let Some(tx) = app.peers.lock().unwrap().get(device) {
        let _ = tx.send(v.to_string());
    }
}

/// Authentification d'une requête via les en-têtes `X-OC-Nonce` / `X-OC-Sig`.
fn check_headers(app: &App, device_id: &str, headers: &HeaderMap) -> Result<(), (StatusCode, String)> {
    let nonce = headers
        .get("x-oc-nonce")
        .and_then(|v| v.to_str().ok())
        .ok_or_else(|| bad("en-tête X-OC-Nonce manquant"))?
        .to_string();
    let sig = headers
        .get("x-oc-sig")
        .and_then(|v| v.to_str().ok())
        .ok_or_else(|| bad("en-tête X-OC-Sig manquant"))?
        .to_string();
    check_sig(app, device_id, &nonce, &sig)
}

/// Authentification d'une requête via nonce/signature portés par le corps.
fn check_sig(app: &App, device_id: &str, nonce: &str, sig: &str) -> Result<(), (StatusCode, String)> {
    app.registry.consume_nonce(device_id, nonce).map_err(bad)?;
    let pk = app
        .registry
        .pubkey_of(device_id)
        .ok_or_else(|| bad("appareil inconnu"))?;
    verify_sig(&pk, nonce, sig).map_err(|e| bad(format!("signature invalide : {e}")))
}

// ------------------------------------------------------------------ routes

async fn healthz() -> Json<Value> {
    Json(json!({ "ok": true, "v": 1 }))
}

#[derive(Deserialize)]
pub struct ChallengeReq {
    pub device_id: String,
}

#[derive(Serialize)]
pub struct ChallengeResp {
    pub nonce: String,
    pub ttl: u64,
}

async fn challenge(State(app): State<Arc<App>>, Json(req): Json<ChallengeReq>) -> Api<ChallengeResp> {
    if req.device_id.trim().is_empty() || req.device_id.len() > 64 {
        return Err(bad("device_id invalide"));
    }
    let nonce = app.registry.new_nonce(&req.device_id);
    Ok(Json(ChallengeResp { nonce, ttl: 60 }))
}

#[derive(Deserialize)]
pub struct RegisterReq {
    pub device_id: String,
    pub role: Role,
    pub name: String,
    /// Point public SEC1 (65 octets) en base64 (authentification ECDSA).
    pub pubkey: String,
    /// Clé publique dédiée ECDH (optionnelle : défaut = `pubkey`).
    pub dh_pubkey: Option<String>,
}

#[derive(Serialize)]
pub struct OkResp {
    pub ok: bool,
}

async fn register(
    State(app): State<Arc<App>>,
    headers: HeaderMap,
    Json(req): Json<RegisterReq>,
) -> Api<OkResp> {
    // La publique envoyée doit être une courbe valide.
    let raw = decode_b64(&req.pubkey)?;
    p256::PublicKey::from_sec1_bytes(&raw).map_err(|e| bad(format!("publique invalide : {e}")))?;
    if let Some(dh) = &req.dh_pubkey {
        let raw = decode_b64(dh)?;
        p256::PublicKey::from_sec1_bytes(&raw).map_err(|e| bad(format!("publique dh invalide : {e}")))?;
    }

    // Rotation de clé : autorisée uniquement prouvée par l'ancienne clé.
    if let Some(old) = app.registry.pubkey_of(&req.device_id) {
        let old_dh = app.registry.dh_pubkey_of(&req.device_id);
        let new_dh = req
            .dh_pubkey
            .clone()
            .filter(|d| !d.is_empty())
            .unwrap_or_else(|| req.pubkey.clone());
        if old != req.pubkey || old_dh.as_deref() != Some(new_dh.as_str()) {
            check_headers(&app, &req.device_id, &headers)?;
        }
    }
    app.registry
        .register(
            &req.device_id,
            req.role,
            &req.name,
            &req.pubkey,
            req.dh_pubkey.clone().filter(|d| !d.is_empty()),
        )
        .map_err(bad)?;
    Ok(Json(OkResp { ok: true }))
}

#[derive(Deserialize)]
pub struct AnnounceReq {
    pub device_id: String,
    pub nonce: String,
    pub sig: String,
}

#[derive(Serialize)]
pub struct AnnounceResp {
    pub code: String,
    pub ttl: u64,
}

async fn announce(State(app): State<Arc<App>>, Json(req): Json<AnnounceReq>) -> Api<AnnounceResp> {
    check_sig(&app, &req.device_id, &req.nonce, &req.sig)?;
    let code = app.registry.announce(&req.device_id).map_err(bad)?;
    tracing::info!(device = %req.device_id, "code d'appairage généré");
    Ok(Json(AnnounceResp { code, ttl: 180 }))
}

#[derive(Deserialize)]
pub struct JoinReq {
    pub code: String,
    pub phone_id: String,
    pub phone_pubkey: String,
    pub phone_dh_pubkey: Option<String>,
    pub phone_name: String,
}

#[derive(Serialize)]
pub struct JoinResp {
    pub pc_id: String,
    pub pc_name: String,
    pub pc_pubkey: String,
    pub pc_dh_pubkey: String,
}

async fn join(State(app): State<Arc<App>>, Json(req): Json<JoinReq>) -> Api<JoinResp> {
    let raw = decode_b64(&req.phone_pubkey)?;
    p256::PublicKey::from_sec1_bytes(&raw).map_err(|e| bad(format!("publique invalide : {e}")))?;
    if let Some(dh) = &req.phone_dh_pubkey {
        let raw = decode_b64(dh)?;
        p256::PublicKey::from_sec1_bytes(&raw).map_err(|e| bad(format!("publique dh invalide : {e}")))?;
    }

    let (pc_id, pc_name, pc_pubkey) = app
        .registry
        .join(
            &req.code,
            &req.phone_id,
            &req.phone_pubkey,
            req.phone_dh_pubkey.as_deref(),
            &req.phone_name,
        )
        .map_err(bad)?;
    let pc_dh_pubkey = app
        .registry
        .dh_pubkey_of(&pc_id)
        .unwrap_or_else(|| pc_pubkey.clone());

    // On informe le PC en direct (il calcule la clé E2E dès réception).
    push(
        &app,
        &pc_id,
        json!({
            "t": "pair.join",
            "phone_id": req.phone_id,
            "phone_pubkey": req.phone_pubkey,
            "phone_dh_pubkey": req.phone_dh_pubkey.clone().filter(|d| !d.is_empty())
                .unwrap_or_else(|| req.phone_pubkey.clone()),
            "phone_name": req.phone_name,
        }),
    );
    tracing::info!(pc = %pc_id, phone = %req.phone_id, "demande d'appairage acceptée (code valide)");
    Ok(Json(JoinResp {
        pc_id,
        pc_name,
        pc_pubkey,
        pc_dh_pubkey,
    }))
}

#[derive(Deserialize)]
pub struct FinalizeReq {
    pub phone_id: String,
}

#[derive(Serialize)]
pub struct FinalizeResp {
    pub pc_id: String,
    pub peer_pubkey: String,
    pub peer_dh_pubkey: String,
}

async fn finalize(State(app): State<Arc<App>>, Json(req): Json<FinalizeReq>) -> Api<FinalizeResp> {
    let (pc_id, pc_pubkey, phone_pubkey, phone_dh_pubkey) =
        app.registry.finalize(&req.phone_id).map_err(bad)?;
    let peer_dh_pubkey = app
        .registry
        .dh_pubkey_of(&pc_id)
        .unwrap_or_else(|| pc_pubkey.clone());
    push(
        &app,
        &pc_id,
        json!({
            "t": "pair.done",
            "phone_id": req.phone_id,
            "phone_pubkey": phone_pubkey,
            "phone_dh_pubkey": phone_dh_pubkey,
        }),
    );
    tracing::info!(pc = %pc_id, phone = %req.phone_id, "appairage finalisé");
    Ok(Json(FinalizeResp {
        pc_id,
        peer_pubkey: pc_pubkey,
        peer_dh_pubkey,
    }))
}

#[derive(Deserialize)]
pub struct StatusQuery {
    pub device: String,
}

#[derive(Deserialize)]
pub struct UnpairReq {
    pub device_id: String,
    pub nonce: String,
    pub sig: String,
}

async fn unpair(State(app): State<Arc<App>>, Json(req): Json<UnpairReq>) -> Api<OkResp> {
    check_sig(&app, &req.device_id, &req.nonce, &req.sig)?;
    let peer = app.registry.peer_of(&req.device_id);
    app.registry.unpair(&req.device_id).map_err(bad)?;
    if let Some(peer) = peer {
        push(&app, &peer, json!({ "t": "unpaired", "by": req.device_id }));
        tracing::info!(by = %req.device_id, "paire dissoute");
    }
    Ok(Json(OkResp { ok: true }))
}

async fn status(
    State(app): State<Arc<App>>,
    Query(q): Query<StatusQuery>,
    headers: HeaderMap,
) -> Api<crate::state::Status> {
    check_headers(&app, &q.device, &headers)?;
    Ok(Json(app.registry.status(&q.device)))
}

// ------------------------------------------------------------------ websocket

#[derive(Deserialize)]
pub struct WsQuery {
    pub device: String,
}

async fn ws_handler(
    State(app): State<Arc<App>>,
    Query(q): Query<WsQuery>,
    ws: WebSocketUpgrade,
) -> Response {
    ws.on_upgrade(move |socket| handle_ws(app, q.device, socket))
        .into_response()
}

async fn handle_ws(app: Arc<App>, device: String, socket: WebSocket) {
    let (mut sink, mut stream) = socket.split();

    // Défi : le client doit signer ce nonce avec sa clé privée (jamais transmise).
    let nonce = app.registry.new_nonce(&device);
    let challenge = json!({ "t": "auth.challenge", "nonce": nonce }).to_string();
    if sink.send(Message::Text(challenge)).await.is_err() {
        return;
    }

    let authed = match tokio::time::timeout(Duration::from_secs(15), stream.next()).await {
        Ok(Some(Ok(Message::Text(text)))) => {
            let v: Value = serde_json::from_str(&text).unwrap_or(Value::Null);
            v.get("t").and_then(|t| t.as_str()) == Some("auth")
                && v.get("nonce").and_then(|n| n.as_str()) == Some(nonce.as_str())
                && check_sig(&app, &device, &nonce, v.get("sig").and_then(|s| s.as_str()).unwrap_or(""))
                    .is_ok()
        }
        _ => false,
    };

    if !authed {
        let _ = sink
            .send(Message::Text(
                json!({ "t": "error", "error": "authentification refusée" }).to_string(),
            ))
            .await;
        tracing::warn!(device = %device, "authentification WS refusée");
        return;
    }

    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<String>();
    let self_tx = tx.clone();
    app.peers.lock().unwrap().insert(device.clone(), tx);
    let _ = self_tx.send(json!({ "t": "auth.ok" }).to_string());
    notify_peer(&app, &device, "online");
    tracing::info!(device = %device, "connecté au relais");

    let forward = tokio::spawn(async move {
        while let Some(text) = rx.recv().await {
            if sink.send(Message::Text(text)).await.is_err() {
                break;
            }
        }
    });

    while let Some(item) = stream.next().await {
        let text = match item {
            Ok(Message::Text(t)) => t,
            Ok(_) => continue,
            Err(_) => break,
        };
        let v: Value = match serde_json::from_str(&text) {
            Ok(v) => v,
            Err(_) => continue,
        };
        match v.get("t").and_then(|t| t.as_str()) {
            Some("ping") => {
                let _ = self_tx.send(json!({ "t": "pong" }).to_string());
            }
            Some("relay") => {
                let payload = v.get("payload").and_then(|p| p.as_str()).unwrap_or("").to_string();
                let to = v
                    .get("to")
                    .and_then(|t| t.as_str())
                    .map(|s| s.to_string())
                    .or_else(|| app.registry.peer_of(&device));
                let Some(to) = to else {
                    let _ = self_tx.send(json!({ "t": "error", "error": "aucun pair appairé" }).to_string());
                    continue;
                };
                // Le serveur ne lit jamais `payload` : paquets opaques (E2E).
                let out = json!({ "t": "relay", "from": device, "payload": payload }).to_string();
                let delivered = app
                    .peers
                    .lock()
                    .unwrap()
                    .get(&to)
                    .map(|tx| tx.send(out).is_ok())
                    .unwrap_or(false);
                if !delivered {
                    let _ = self_tx.send(
                        json!({ "t": "peer", "state": "offline", "device": to }).to_string(),
                    );
                }
            }
            _ => {}
        }
    }

    forward.abort();
    {
        let mut peers = app.peers.lock().unwrap();
        if let Some(tx) = peers.get(&device) {
            if tx.same_channel(&self_tx) {
                peers.remove(&device);
            }
        }
    }
    notify_peer(&app, &device, "offline");
    tracing::info!(device = %device, "déconnecté");
}

fn notify_peer(app: &App, device: &str, state: &str) {
    if let Some(peer) = app.registry.peer_of(device) {
        let msg = json!({ "t": "peer", "state": state, "device": device }).to_string();
        if let Some(tx) = app.peers.lock().unwrap().get(&peer) {
            let _ = tx.send(msg);
        }
    }
}
