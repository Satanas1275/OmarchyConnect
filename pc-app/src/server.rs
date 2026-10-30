//! Serveur OmarchyConnect : WebSocket (protocole) + upload de fichiers.
//!
//! Deux entrées partagent la même logique `dispatch` :
//!  - le serveur LAN direct (`/ws`, jeton local) pour le développement ;
//!  - les transports E2E (relais dans `relay.rs`, Bluetooth dans `bt.rs`) qui
//!    déchiffrent les frames avant de les confier à `dispatch`.
//!
//! Les effets de bord (presse-papiers, notifications, entrée, fichiers) sont
//! délégués à `desktop.rs`. Le code d'appairage LAN est à usage unique.

use std::collections::{HashMap, HashSet};
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

/// Fichier en cours de réception par blocs E2E (`file.begin/chunk/end`).
pub struct PendingFile {
    pub name: String,
    pub data: Vec<u8>,
    pub started: std::time::Instant,
}

/// Durée au-delà de laquelle un transfert abandonné est oublié.
const PENDING_TTL: std::time::Duration = std::time::Duration::from_secs(600);

pub struct AppState {
    pub pc_name: String,
    pub user: String,
    pub since: String,
    pub fingerprint: String,
    pub pairing_code: Mutex<String>,
    pub commands: Vec<Command>,
    pub tokens: Mutex<HashSet<String>>,
    pub upload_dir: PathBuf,
    /// Transferts de fichiers en cours, indexés par id de transfert.
    pub files: Mutex<HashMap<String, PendingFile>>,
    /// Connexions actives (relais + BT) recevant les frames poussées par le PC
    /// (presse-papiers temps réel, etc.).
    pub sinks: Mutex<Vec<tokio::sync::mpsc::UnboundedSender<String>>>,
    /// Dernier presse-papiers connu (évite les boucles téléphone → PC → téléphone).
    pub last_clipboard: Mutex<String>,
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
            files: Mutex::new(HashMap::new()),
            sinks: Mutex::new(Vec::new()),
            last_clipboard: Mutex::new(String::new()),
        }
    }

    /// Enregistre une connexion sortante ; chaque frame poussée par le PC y est
    /// envoyée en clair puis chiffrée par la connexion avec sa clé de session.
    pub fn register_sink(&self) -> tokio::sync::mpsc::UnboundedReceiver<String> {
        let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
        if let Ok(mut sinks) = self.sinks.lock() {
            sinks.retain(|s| !s.is_closed());
            sinks.push(tx);
        }
        rx
    }

    /// Diffuse une frame applicative (en clair) à toutes les connexions.
    pub fn broadcast(&self, frame: String) {
        if let Ok(mut sinks) = self.sinks.lock() {
            sinks.retain(|tx| tx.send(frame.clone()).is_ok());
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
    let mut pushes = st.register_sink();
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

    loop {
        tokio::select! {
            item = rx.next() => {
                let Some(Ok(msg)) = item else { break };
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
            pushed = pushes.recv() => {
                // Diffusion PC -> client (presse-papiers, etc.).
                let Some(text) = pushed else { break };
                if !authed {
                    continue;
                }
                if tx.send(Message::Text(text)).await.is_err() {
                    break;
                }
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
                let text = incoming.str("text").unwrap_or("");
                info!(len = text.chars().count(), "presse-papiers reçu du téléphone");
                if !text.is_empty() {
                    crate::desktop::apply_clipboard(st, text).await;
                }
                None
            }

            "notification" if *authed => {
                let app = incoming.str("app").unwrap_or("Téléphone");
                let title = incoming.str("title").unwrap_or("");
                let text = incoming.str("text").unwrap_or("");
                info!(app, title, "notification du téléphone");
                crate::desktop::notify(app, title, text).await;
                None
            }

            "input" if *authed => {
                crate::desktop::input(&incoming.fields).await;
                None
            }

            // Transfert de fichier E2E : le relais ne voit que des blocs
            // chiffrés ; l'assemblage et l'écriture se font ici.
            "file.begin" if *authed => {
                let id = incoming.str("id").unwrap_or("");
                if id.is_empty() {
                    return Some(error("bad_frame", "file.begin sans id"));
                }
                let name = safe_name(incoming.str("name").unwrap_or("upload.bin"));
                if let Ok(mut files) = st.files.lock() {
                    files.retain(|_, f| f.started.elapsed() < PENDING_TTL);
                    files.insert(
                        id.to_string(),
                        PendingFile {
                            name: name.clone(),
                            data: Vec::new(),
                            started: std::time::Instant::now(),
                        },
                    );
                }
                tracing::debug!(id, name, "réception de fichier démarrée");
                None
            }

            "file.chunk" if *authed => {
                let id = incoming.str("id").unwrap_or("");
                let data = incoming.str("data").unwrap_or("");
                let Ok(chunk) = base64::engine::general_purpose::STANDARD.decode(data) else {
                    return Some(error("bad_frame", "file.chunk base64 invalide"));
                };
                if let Ok(mut files) = st.files.lock() {
                    match files.get_mut(id) {
                        Some(pending) => {
                            if pending.data.len() + chunk.len() > MAX_UPLOAD {
                                files.remove(id);
                                return Some(error("too_large", "fichier trop volumineux"));
                            }
                            pending.data.extend_from_slice(&chunk);
                        }
                        None => return Some(error("no_transfer", id)),
                    }
                }
                None
            }

            "file.end" if *authed => {
                let id = incoming.str("id").unwrap_or("");
                let pending = st.files.lock().ok().and_then(|mut f| f.remove(id));
                let Some(pending) = pending else {
                    return Some(frame(
                        "file.result",
                        json!({ "id": id, "ok": false, "error": "transfert inconnu" }),
                    ));
                };
                let name = if pending.name.is_empty() {
                    safe_name(incoming.str("name").unwrap_or("upload.bin"))
                } else {
                    pending.name.clone()
                };
                Some(save_file(st, id, &name, pending.data).await)
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

/// Palette synchronisée avec le thème Omarchy courant.
///
/// Omarchy expose le thème actif dans `$XDG_STATE_HOME/omarchy/current/`
/// (`theme.name` + `theme/colors.toml`). On retombe sur l'ancien emplacement
/// (`~/.config/omarchy/current/theme`) puis sur une palette sombre par défaut.
fn theme_frame() -> String {
    let (name, palette) = omarchy_theme();
    let get = |key: &str, fallback: &str| {
        palette
            .get(key)
            .cloned()
            .unwrap_or_else(|| fallback.to_string())
    };

    frame(
        "theme",
        json!({
            "name": name,
            "c": {
                "bg": get("background", "#0E1116"),
                "surface": get("dark_background", "#10151C"),
                "surfaceAlt": get("lighter_background", "#16222B"),
                "border": get("selection", "#2A3A46"),
                "borderAccent": get("accent", "#3E6E74"),
                "accent": get("accent", "#A8E6CF"),
                "primary": get("bright_foreground", "#BFE3FF"),
                "text": get("foreground", "#DDE6EE"),
                "muted": get("muted", "#7C8A99"),
                "danger": get("red", "#E88C8C"),
            }
        }),
    )
}

fn omarchy_theme() -> (String, HashMap<String, String>) {
    let mut palette = HashMap::new();
    let mut name = std::env::var("OMARCHY_THEME").unwrap_or_else(|_| "omarchy".into());

    let state = std::env::var("XDG_STATE_HOME")
        .map(PathBuf::from)
        .or_else(|_| std::env::var("HOME").map(|h| PathBuf::from(h).join(".local/state")))
        .ok()
        .map(|d| d.join("omarchy/current"));

    let mut candidates = Vec::new();
    if let Some(dir) = &state {
        candidates.push(dir.join("theme"));
        if let Ok(value) = std::fs::read_to_string(dir.join("theme.name")) {
            let value = value.trim();
            if !value.is_empty() {
                name = value.to_string();
            }
        }
    }
    if let Ok(config) = std::env::var("XDG_CONFIG_HOME") {
        candidates.push(PathBuf::from(config).join("omarchy/current/theme"));
    } else if let Ok(home) = std::env::var("HOME") {
        candidates.push(PathBuf::from(home).join(".config/omarchy/current/theme"));
    }

    for dir in candidates {
        let path = dir.join("colors.toml");
        let Ok(raw) = std::fs::read_to_string(&path) else {
            continue;
        };
        let Ok(value) = toml::from_str::<toml::Value>(&raw) else {
            continue;
        };
        if let Some(table) = value.as_table() {
            for (key, value) in table {
                if let Some(color) = value.as_str() {
                    if color.starts_with('#') {
                        palette.insert(key.clone(), color.to_string());
                    }
                }
            }
        }
        break;
    }

    (name, palette)
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

/// Nom de fichier sûr : pas de séparateur de chemin, pas de nom caché.
fn safe_name(raw: &str) -> String {
    let base = raw.rsplit(['/', '\\']).next().unwrap_or("upload.bin");
    let cleaned: String = base.chars().filter(|c| !c.is_control()).collect();
    let trimmed = cleaned.trim().trim_start_matches('.').to_string();
    if trimmed.is_empty() {
        "upload.bin".to_string()
    } else {
        trimmed.chars().take(180).collect()
    }
}

/// Évite d'écraser un fichier existant en suffixant ` (n)`.
fn unique_dest(dir: &std::path::Path, name: &str) -> PathBuf {
    let candidate = dir.join(name);
    if !candidate.exists() {
        return candidate;
    }
    let path = std::path::Path::new(name);
    let stem = path.file_stem().and_then(|s| s.to_str()).unwrap_or("upload");
    let ext = path.extension().and_then(|s| s.to_str()).unwrap_or("");
    for n in 1..1000 {
        let name = if ext.is_empty() {
            format!("{stem} ({n})")
        } else {
            format!("{stem} ({n}).{ext}")
        };
        let candidate = dir.join(name);
        if !candidate.exists() {
            return candidate;
        }
    }
    candidate
}

/// Écrit un fichier assemblé et renvoie `file.result` au téléphone.
async fn save_file(st: &AppState, id: &str, name: &str, data: Vec<u8>) -> String {
    let dir = st.upload_dir.clone();
    if let Err(e) = tokio::fs::create_dir_all(&dir).await {
        return frame(
            "file.result",
            json!({ "id": id, "name": name, "ok": false, "error": e.to_string() }),
        );
    }
    let dest = unique_dest(&dir, name);
    match tokio::fs::write(&dest, &data).await {
        Ok(()) => {
            info!(file = %dest.display(), size = data.len(), "fichier reçu du téléphone (E2E)");
            frame(
                "file.result",
                json!({
                    "id": id,
                    "name": name,
                    "ok": true,
                    "path": dest.display().to_string(),
                    "size": data.len(),
                }),
            )
        }
        Err(e) => frame(
            "file.result",
            json!({ "id": id, "name": name, "ok": false, "error": e.to_string() }),
        ),
    }
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
