//! Serveur OmarchyConnect : WebSocket (protocole) + upload de fichiers.
//!
//! Sécurité :
//!  - tout passe par le jeton délivré à l'appairage (trame `auth`, jamais dans l'URL) ;
//!  - le code d'appairage est à usage unique et expire (30 s) ;
//!  - `fingerprint` est annoncé au téléphone (`hello.fp`) pour détecter un MITM.
//!
//! À venir (voir README) : injection d'input (enigo), presse-papiers (arboard),
//! notifications bureau (notify-rust / KDE DBus), TLS direct (rustls).

use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{DefaultBodyLimit, Multipart, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::Router;
use base64::Engine;
use futures_util::{SinkExt, StreamExt};
use rand::Rng;
use serde_json::json;
use tracing::{info, warn};

use crate::config::Command;
use crate::protocol::{error, frame, Incoming};

pub const MAX_UPLOAD: usize = 1024 * 1024 * 1024; // 1 Go

pub struct AppState {
    pub pc_name: String,
    pub user: String,
    pub since: String,
    pub fingerprint: String,
    pub pairing_code: Mutex<String>,
    pub commands: Vec<Command>,
    pub tokens: Mutex<HashSet<String>>,
    pub upload_dir: PathBuf,
}

impl AppState {
    pub fn new(
        commands: Vec<Command>,
        pairing_code: String,
        fingerprint: String,
        upload_dir: PathBuf,
    ) -> Self {
        AppState {
            pc_name: hostname(),
            user: whoami(),
            since: "2026".to_string(),
            fingerprint,
            pairing_code: Mutex::new(pairing_code),
            commands,
            tokens: Mutex::new(HashSet::new()),
            upload_dir,
        }
    }

    fn authorized(&self, token: &str) -> bool {
        self.tokens.lock().map(|t| t.contains(token)).unwrap_or(false)
    }

    /// Code à usage unique : consommé dès qu'il est validé.
    fn consume_code(&self, candidate: &str) -> bool {
        let mut code = match self.pairing_code.lock() {
            Ok(c) => c,
            Err(_) => return false,
        };
        if code.eq_ignore_ascii_case(candidate.trim()) && !code.is_empty() {
            *code = String::new();
            true
        } else {
            false
        }
    }

    fn new_token(&self) -> String {
        let mut rng = rand::thread_rng();
        let raw: Vec<u8> = (0..32).map(|_| rng.gen()).collect();
        let token = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(raw);
        if let Ok(mut t) = self.tokens.lock() {
            t.insert(token.clone());
        }
        token
    }
}

pub fn router(state: Arc<AppState>) -> Router {
    Router::new()
        .route("/ws", get(ws_handler))
        .route("/api/files", post(upload))
        .route("/healthz", get(healthz))
        .layer(DefaultBodyLimit::max(MAX_UPLOAD))
        .with_state(state)
}

async fn healthz() -> axum::Json<serde_json::Value> {
    axum::Json(json!({ "ok": true, "v": 1 }))
}

async fn ws_handler(State(st): State<Arc<AppState>>, ws: WebSocketUpgrade) -> Response {
    ws.on_upgrade(move |socket| handle_socket(socket, st))
}

async fn handle_socket(socket: WebSocket, st: Arc<AppState>) {
    let (mut tx, mut rx) = socket.split();
    let mut authed = false;

    let hello = frame(
        "hello",
        json!({
            "pc": st.pc_name,
            "user": st.user,
            "since": st.since,
            "fp": st.fingerprint,
        }),
    );
    if tx.send(Message::Text(hello)).await.is_err() {
        return;
    }

    while let Some(Ok(msg)) = rx.next().await {
        let raw = match msg {
            Message::Text(t) => t,
            Message::Binary(b) => String::from_utf8_lossy(&b).into_owned(),
            Message::Close(_) => break,
            _ => continue,
        };

        let Some(incoming) = Incoming::parse(&raw) else {
            let _ = tx.send(Message::Text(error("bad_frame", "JSON invalide"))).await;
            continue;
        };

        let reply: Option<String> = dispatch(&st, incoming, &mut authed).await;

        if let Some(text) = reply {
            if tx.send(Message::Text(text)).await.is_err() {
                break;
            }
        }
    }

    info!("socket fermée");
}

/// Logique de traitement d'une trame applicative, partagée entre le serveur
/// direct (LAN) et le transport relais (`relay.rs`).
///
/// `authed` est mis à jour par effet de bord (pairing/jeton réussis).
pub(crate) async fn dispatch(
    st: &Arc<AppState>,
    incoming: Incoming,
    authed: &mut bool,
) -> Option<String> {
    match incoming.t.as_str() {
            "ping" => Some(frame("pong", json!({}))),

            "pair.request" => {
                let code = incoming.str("code").unwrap_or("");
                let device = incoming.str("device").unwrap_or("phone");
                if st.consume_code(code) {
                    let token = st.new_token();
                    *authed = true;
                    info!(device, "appairage accepté");
                    Some(frame(
                        "pair.ok",
                        json!({ "token": token, "user": st.user, "since": st.since }),
                    ))
                } else {
                    warn!(device, "code d'appairage invalide");
                    Some(error("pair_denied", "code invalide ou expiré"))
                }
            }

            "auth" => {
                let token = incoming.str("token").unwrap_or("");
                if st.authorized(token) {
                    *authed = true;
                    Some(frame("auth.ok", json!({ "user": st.user })))
                } else {
                    Some(error("unauthorized", "jeton inconnu"))
                }
            }

            "commands.list" if *authed => Some(commands_frame(st)),

            "command.run" if *authed => {
                let id = incoming.str("id").unwrap_or("");
                Some(run_command(st, id).await)
            }

            "theme.get" => Some(theme_frame()),

            "clipboard" if *authed => {
                let len = incoming
                    .str("text")
                    .map(|t| t.chars().count())
                    .unwrap_or(0);
                info!(len, "presse-papier reçu (-> à appliquer sur le PC)");
                None
            }

            "notification" if *authed => {
                let app = incoming.str("app").unwrap_or("?");
                let title = incoming.str("title").unwrap_or("");
                info!(app, title, "notification du téléphone reçue");
                None
            }

            "input" if *authed => {
                let kind = incoming.str("kind").unwrap_or("?");
                // TODO: injection réelle (enigo) — voir README pc-app
                tracing::debug!(kind, fields = ?incoming.fields, "input");
                None
            }

            "file.offer" => None,

            t if !*authed => Some(error("unauthorized", &format!("{t} nécessite auth"))),
            t => Some(error("unknown_type", t)),
        }
}

fn commands_frame(st: &AppState) -> String {
    let items: Vec<_> = st
        .commands
        .iter()
        .map(|c| {
            json!({
                "id": c.id,
                "name": c.name,
                "description": c.description,
                "danger": c.danger,
            })
        })
        .collect();
    frame("commands", json!({ "items": items }))
}

async fn run_command(st: &AppState, id: &str) -> String {
    let Some(cmd) = st.commands.iter().find(|c| c.id == id) else {
        return error("unknown_command", id);
    };
    if cmd.run.is_empty() {
        return error("not_executable", "commande sans `run`");
    }

    info!(id = cmd.id, run = cmd.run, "exécution de la commande");
    let output = tokio::process::Command::new("sh")
        .arg("-c")
        .arg(&cmd.run)
        .output()
        .await;

    match output {
        Ok(out) => {
            let mut text = String::from_utf8_lossy(&out.stdout).into_owned();
            if !out.stderr.is_empty() {
                text.push('\n');
                text.push_str(&String::from_utf8_lossy(&out.stderr));
            }
            let text: String = text.chars().take(16_384).collect();
            frame(
                "command.result",
                json!({
                    "id": cmd.id,
                    "name": cmd.name,
                    "ok": out.status.success(),
                    "out": text,
                }),
            )
        }
        Err(e) => error("exec_failed", &e.to_string()),
    }
}

/// Palette par défaut : sera lue depuis la config Omarchy (~/.config/omarchy/*).
fn theme_frame() -> String {
    frame(
        "theme",
        json!({
            "name": std::env::var("OMARCHY_THEME").unwrap_or_else(|_| "omarchy".into()),
            "c": {
                "bg": "#0E1116",
                "surface": "#10151C",
                "surfaceAlt": "#16222B",
                "border": "#2A3A46",
                "borderAccent": "#3E6E74",
                "accent": "#A8E6CF",
                "primary": "#BFE3FF",
                "text": "#DDE6EE",
                "muted": "#7C8A99",
                "danger": "#E88C8C",
            }
        }),
    )
}

/// POST /api/files — multipart (`name` + `file`), auth par jeton.
pub async fn upload(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
    mut multipart: Multipart,
) -> Result<impl IntoResponse, (StatusCode, String)> {
    let bearer = headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .unwrap_or_default()
        .trim_start_matches("Bearer ")
        .to_string();

    if !st.authorized(&bearer) {
        return Err((StatusCode::UNAUTHORIZED, "jeton invalide".into()));
    }

    let mut name = String::from("upload.bin");
    let mut bytes: Option<Vec<u8>> = None;

    while let Ok(Some(field)) = multipart.next_field().await {
        let field_name = field.name().unwrap_or_default().to_string();
        match field_name.as_str() {
            "name" => {
                if let Ok(v) = field.bytes().await {
                    name = String::from_utf8_lossy(&v).to_string();
                }
            }
            "file" => bytes = field.bytes().await.ok().map(|b| b.to_vec()),
            _ => {}
        }
    }

    let Some(bytes) = bytes else {
        return Err((StatusCode::BAD_REQUEST, "champ `file` manquant".into()));
    };

    let safe = PathBuf::from(name.replace(['/', '\\'], "_"));
    let dest = st.upload_dir.join(&safe);
    tokio::fs::create_dir_all(&st.upload_dir)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    tokio::fs::write(&dest, &bytes)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    info!(file = %dest.display(), size = bytes.len(), "fichier reçu du téléphone");
    Ok(axum::Json(json!({ "ok": true, "path": dest.display().to_string() })))
}

fn hostname() -> String {
    std::fs::read_to_string("/etc/hostname")
        .map(|s| s.trim().to_string())
        .ok()
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "omarchy-pc".into())
}

fn whoami() -> String {
    std::env::var("USER").unwrap_or_else(|_| "user".into())
}
