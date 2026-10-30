//! Client du relais central OmarchyConnect.
//!
//! Le PC se connecte au serveur local `http://127.0.0.1:6716` (publié sur
//! `https://omarchy-connect.rocknite-studio.com/` par le reverse proxy).
//!
//! Sécurité :
//!  - **clé EC P-256 privée générée localement, jamais transmise** : le serveur
//!    ne reçoit que la clé publique ; l'auth = signature ECDSA d'un nonce à
//!    usage unique (anti-rejeu), sur les requêtes HTTP comme sur la WebSocket ;
//!  - **chiffrement de bout en bout** : la clé de session K est dérivée par
//!    ECDH (clé statique PC ↔ clé statique téléphone) + HKDF-SHA256 ; les
//!    frames applicatives transitent dans `{"t":"relay","payload":<AES-256-GCM>}`
//!    que le serveur relaie **sans jamais pouvoir les lire** ;
//!  - le serveur seul connaît « qui se connecte à qui » (paires persistées).

use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Key, Nonce};
use anyhow::{anyhow, bail, Context, Result};
use base64::Engine as _;
use futures_util::{SinkExt, Stream, StreamExt};
use hkdf::Hkdf;
use p256::ecdsa::signature::Signer;
use p256::ecdsa::{Signature, SigningKey};
use p256::elliptic_curve::sec1::ToEncodedPoint;
use p256::{PublicKey, SecretKey};
use rand::Rng;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::Sha256;
use tokio_tungstenite::tungstenite::Message;
use tracing::{info, warn};

use crate::protocol::Incoming;
use crate::server::{dispatch, AppState};

// ---------------------------------------------------------------- identité

/// Identité relais du PC : `device_id` + clé privée EC P-256 (**locale**).
#[derive(Clone, Serialize, Deserialize)]
pub struct Identity {
    pub device_id: String,
    /// 32 octets hex. Ne quitte jamais cette machine.
    pub privkey: String,
}

impl Identity {
    pub fn load_or_create(dir: &Path) -> Result<Identity> {
        let path = dir.join("relay.json");
        if let Ok(raw) = std::fs::read_to_string(&path) {
            if let Ok(id) = serde_json::from_str::<Identity>(&raw) {
                if id.privkey.len() == 64 && id.device_id.len() >= 16 {
                    // Vérifie que la clé est bien une courbe valide.
                    if SigningKey::from_slice(&hex_decode(&id.privkey)?).is_ok() {
                        return Ok(id);
                    }
                }
            }
            warn!("relay.json illisible, régénération de l'identité");
        }

        std::fs::create_dir_all(dir)?;
        let mut rng = rand::thread_rng();
        let device_id: String = (0..16).map(|_| format!("{:02x}", rng.gen::<u8>())).collect();
        let signing = SigningKey::random(&mut rng);
        let id = Identity {
            device_id,
            privkey: hex::encode(signing.to_bytes()),
        };
        write_private(&path, &serde_json::to_string_pretty(&id)?)?;
        info!(device = %id.device_id, "identité relais créée (clé EC locale)");
        Ok(id)
    }

    fn signing_key(&self) -> Result<SigningKey> {
        SigningKey::from_slice(&hex_decode(&self.privkey)?).map_err(|e| anyhow!("clé invalide : {e}"))
    }

    /// Clé publique SEC1 non compressée (65 octets), base64.
    pub fn pubkey_b64(&self) -> Result<String> {
        let sk = SecretKey::from_slice(&hex_decode(&self.privkey)?)?;
        let point = sk.public_key().to_encoded_point(false);
        Ok(b64(point.as_bytes()))
    }

    /// Signature ECDSA/SHA-256 (DER, base64) — sert à prouver la possession
    /// de la clé privée sans jamais la transmettre.
    pub fn sign(&self, msg: &str) -> Result<String> {
        let sig: Signature = self.signing_key()?.sign(msg.as_bytes());
        Ok(b64(sig.to_der().as_bytes()))
    }

    /// K = HKDF-SHA256(ECDH(privée locale, publique du téléphone)).
    pub fn derive_session_key(&self, peer_pubkey_b64: &str) -> Result<[u8; 32]> {
        let signing = self.signing_key()?;
        let peer_raw = b64decode(peer_pubkey_b64)?;
        let peer = PublicKey::from_sec1_bytes(&peer_raw).map_err(|e| anyhow!("publique pair : {e}"))?;
        let shared = p256::ecdh::diffie_hellman(signing.as_nonzero_scalar(), peer.as_affine());
        let hk = Hkdf::<Sha256>::new(Some(b"omarchy-connect-v1"), shared.raw_secret_bytes());
        let mut okm = [0u8; 32];
        hk.expand(b"payload-key-aes256", &mut okm)
            .map_err(|_| anyhow!("HKDF"))?;
        Ok(okm)
    }
}

/// Appairage validé : pair + clé de session dérivée.
#[derive(Serialize, Deserialize)]
struct PairState {
    peer: String,
    peer_pubkey: String,
    k: String, // hex32
}

fn pair_path(dir: &Path) -> PathBuf {
    dir.join("relay-pair.json")
}

fn load_pair(dir: &Path) -> Option<PairState> {
    std::fs::read_to_string(pair_path(dir))
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .filter(|p: &PairState| p.k.len() == 64)
}

fn store_pair(dir: &Path, pair: &PairState) -> Result<()> {
    std::fs::create_dir_all(dir)?;
    write_private(&pair_path(dir), &serde_json::to_string_pretty(pair)?)?;
    Ok(())
}

/// Écrit un fichier de secret avec les permissions les plus restrictives (unix).
fn write_private(path: &Path, content: &str) -> Result<()> {
    std::fs::write(path, content)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))?;
    }
    Ok(())
}

// ---------------------------------------------------------------- crypto E2E

pub(crate) fn encrypt(k: &[u8; 32], plaintext: &str) -> Result<String> {
    let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(k));
    let mut iv = [0u8; 12];
    rand::thread_rng().fill(&mut iv);
    let ct = cipher
        .encrypt(Nonce::from_slice(&iv), plaintext.as_bytes())
        .map_err(|_| anyhow!("échec du chiffrement"))?;
    let mut out = Vec::with_capacity(12 + ct.len());
    out.extend_from_slice(&iv);
    out.extend_from_slice(&ct);
    Ok(b64(&out))
}

pub(crate) fn decrypt(k: &[u8; 32], payload: &str) -> Result<String> {
    let data = b64decode(payload)?;
    if data.len() < 13 {
        bail!("payload trop court");
    }
    let (iv, ct) = data.split_at(12);
    let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(k));
    let pt = cipher
        .decrypt(Nonce::from_slice(iv), ct)
        .map_err(|_| anyhow!("déchiffrement impossible (clé de session ?)"))?;
    String::from_utf8(pt).map_err(|e| anyhow!("utf8 : {e}"))
}

// ------------------------------------------------------- protocole relais HTTP

async fn post_json(
    http: &reqwest::Client,
    url: &str,
    body: &Value,
) -> Result<Value> {
    let resp = http.post(url).json(body).send().await.context("connexion relais")?;
    let status = resp.status();
    let text = resp.text().await.unwrap_or_default();
    if !status.is_success() {
        bail!("{url} → {status} : {text}");
    }
    serde_json::from_str(&text).with_context(|| format!("réponse illisible : {text}"))
}

async fn challenge(http: &reqwest::Client, base: &str, ident: &Identity) -> Result<String> {
    let v = post_json(
        http,
        &format!("{base}/api/challenge"),
        &json!({ "device_id": ident.device_id }),
    )
    .await?;
    let nonce = v["nonce"].as_str().unwrap_or_default().to_string();
    if nonce.is_empty() {
        bail!("challenge sans nonce");
    }
    Ok(nonce)
}

async fn register(http: &reqwest::Client, base: &str, ident: &Identity, name: &str) -> Result<()> {
    let v = post_json(
        http,
        &format!("{base}/api/register"),
        &json!({
            "device_id": ident.device_id,
            "role": "pc",
            "name": name,
            "pubkey": ident.pubkey_b64()?,
        }),
    )
    .await?;
    if v["ok"] == true {
        Ok(())
    } else {
        bail!("register : {v}");
    }
}

async fn announce(http: &reqwest::Client, base: &str, ident: &Identity) -> Result<String> {
    let nonce = challenge(http, base, ident).await?;
    let sig = ident.sign(&nonce)?;
    let v = post_json(
        http,
        &format!("{base}/api/pair/announce"),
        &json!({ "device_id": ident.device_id, "nonce": nonce, "sig": sig }),
    )
    .await?;
    let code = v["code"].as_str().unwrap_or_default().to_string();
    if code.is_empty() {
        bail!("announce sans code : {v}");
    }
    Ok(code)
}

async fn signed_status(http: &reqwest::Client, base: &str, ident: &Identity) -> Result<Value> {
    let nonce = challenge(http, base, ident).await?;
    let sig = ident.sign(&nonce)?;
    let resp = http
        .get(format!("{base}/api/pair/status"))
        .query(&[("device", ident.device_id.as_str())])
        .header("X-OC-Nonce", &nonce)
        .header("X-OC-Sig", &sig)
        .send()
        .await
        .context("connexion relais")?;
    let status = resp.status();
    let text = resp.text().await.unwrap_or_default();
    if !status.is_success() {
        bail!("status → {status} : {text}");
    }
    Ok(serde_json::from_str(&text)?)
}

fn ws_url(base: &str, device_id: &str) -> String {
    let root = base
        .trim_end_matches('/')
        .replacen("https://", "wss://", 1)
        .replacen("http://", "ws://", 1);
    format!("{root}/ws?device={device_id}")
}

// ------------------------------------------------------------------ session

/// Boucle principale : (re)connexion au relais avec backoff.
pub async fn run(state: std::sync::Arc<AppState>, base: String, config_dir: PathBuf) {
    let ident = match Identity::load_or_create(&config_dir) {
        Ok(i) => i,
        Err(e) => {
            warn!("identité relais : {e:#}");
            return;
        }
    };
    info!(device = %ident.device_id, relay = %base, "client relais");

    let http = match reqwest::Client::builder()
        .timeout(Duration::from_secs(10))
        .build()
    {
        Ok(c) => c,
        Err(e) => {
            warn!("client HTTP : {e}");
            return;
        }
    };

    let mut backoff = 1u64;
    loop {
        match session(&state, &http, &base, &ident, &config_dir).await {
            Ok(()) => backoff = 1,
            Err(e) => warn!("relais déconnecté : {e:#}"),
        }
        tokio::time::sleep(Duration::from_secs(backoff)).await;
        backoff = (backoff * 2).min(30);
    }
}

async fn session(
    state: &std::sync::Arc<AppState>,
    http: &reqwest::Client,
    base: &str,
    ident: &Identity,
    config_dir: &Path,
) -> Result<()> {
    // 1. Enregistrement (TOFU) : uniquement la clé **publique** part vers le serveur.
    register(http, base, ident, &state.pc_name)
        .await
        .context("enregistrement relais")?;

    // 2. WebSocket + authentification par signature du nonce.
    let url = ws_url(base, &ident.device_id);
    let (ws, _) = tokio_tungstenite::connect_async(&url)
        .await
        .with_context(|| format!("websocket {url}"))?;
    let (mut sink, mut stream) = ws.split();

    let challenge_frame = recv_json(&mut stream).await?;
    if challenge_frame["t"] != "auth.challenge" {
        bail!("première trame inattendue : {challenge_frame}");
    }
    let nonce = challenge_frame["nonce"].as_str().unwrap_or_default().to_string();
    sink.send(Message::Text(
        json!({ "t": "auth", "nonce": nonce, "sig": ident.sign(&nonce)? }).to_string(),
    ))
    .await?;
    let auth_resp = recv_json(&mut stream).await?;
    if auth_resp["t"] == "error" {
        bail!("authentification refusée : {}", auth_resp["error"]);
    }
    if auth_resp["t"] != "auth.ok" {
        bail!("réponse d'auth inattendue : {auth_resp}");
    }
    info!("connecté et authentifié sur le relais");

    // Connexion sortante vers le téléphone pour les frames poussées par le PC
    // (presse-papiers en temps réel). Le contenu est chiffré ici, donc le
    // relais ne le voit jamais en clair.
    let mut pushes = state.register_sink();

    // 3. Rattrapage d'état (si la dernière séquence appairage a été manquée,
    //    ou si le serveur a perdu ses paires).
    match signed_status(http, base, ident).await {
        Ok(st) => {
            let paired = st["paired"] == true;
            match (paired, load_pair(config_dir)) {
                (true, None) => {
                    let peer = st["peer"].as_str().unwrap_or_default().to_string();
                    let pk = st
                        .get("peer_dh_pubkey")
                        .and_then(|d| d.as_str())
                        .filter(|d| !d.is_empty())
                        .or_else(|| st["peer_pubkey"].as_str())
                        .unwrap_or_default()
                        .to_string();
                    if !pk.is_empty() {
                        let k = ident.derive_session_key(&pk)?;
                        store_pair(
                            config_dir,
                            &PairState { peer: peer.clone(), peer_pubkey: pk, k: hex::encode(k) },
                        )?;
                        info!(peer = %peer, "clé de session dérivée (rattrapage)");
                    }
                }
                (false, Some(_)) => {
                    let _ = std::fs::remove_file(pair_path(config_dir));
                    warn!("le serveur ne connaît plus la paire — réappairage nécessaire");
                }
                _ => {}
            }
        }
        Err(e) => warn!("état relais indisponible : {e:#}"),
    }

    // 4. Boucle : frames relais + (re)annonce du code d'appairage + keepalive.
    let mut announce_tick = tokio::time::interval(Duration::from_secs(5));
    let mut ping_tick = tokio::time::interval(Duration::from_secs(30));
    let mut last_announce: Option<Instant> = None;

    loop {
        tokio::select! {
            item = stream.next() => {
                let Some(item) = item else { bail!("flux WebSocket terminé") };
                let msg = item.map_err(|e| anyhow!("websocket : {e}"))?;
                let text = match msg {
                    Message::Text(t) => t,
                    Message::Close(_) => bail!("WebSocket fermée par le serveur"),
                    _ => continue,
                };
                let v: Value = serde_json::from_str(&text).context("trame illisible")?;
                handle_frame(
                    state, ident, config_dir,
                    &mut sink, v, &mut last_announce,
                ).await?;
            }
            pushed = pushes.recv() => {
                let Some(plain) = pushed else { bail!("canal de diffusion fermé") };
                // Sans paire, il n'y a personne à qui pousser la frame.
                if let Some(pair) = load_pair(config_dir) {
                    let k = hex_decode(&pair.k).map_err(|_| anyhow!("clé locale illisible"))?;
                    let mut k32 = [0u8; 32];
                    if k.len() != 32 {
                        bail!("clé de session locale invalide");
                    }
                    k32.copy_from_slice(&k);
                    let enc = encrypt(&k32, &plain)?;
                    sink.send(Message::Text(
                        json!({ "t": "relay", "to": pair.peer, "payload": enc }).to_string(),
                    ))
                    .await
                    .map_err(|_| anyhow!("envoi WebSocket échoué"))?;
                }
            }
            _ = announce_tick.tick() => {
                if load_pair(config_dir).is_none() {
                    let stale = last_announce.map(|t| t.elapsed() > Duration::from_secs(150)).unwrap_or(true);
                    if stale {
                        match announce(http, base, ident).await {
                            Ok(code) => {
                                last_announce = Some(Instant::now());
                                println!("\n┌── code d'appairage (relais) ────────────────┐");
                                println!("│  {} {:^30} │", code.chars().take(4).collect::<String>(), code.chars().skip(4).collect::<String>());
                                println!("└──────────────────────────────────────────────┘\n");
                                info!(code = %code, "code d'appairage (valable 3 min)");
                            }
                            Err(e) => warn!("annonce impossible : {e:#}"),
                        }
                    }
                }
            }
            _ = ping_tick.tick() => {
                sink.send(Message::Text(json!({ "t": "ping" }).to_string())).await?;
            }
        }
    }
}

async fn recv_json<S>(stream: &mut S) -> Result<Value>
where
    S: Stream<Item = Result<Message, tokio_tungstenite::tungstenite::Error>> + Unpin,
{
    let item = tokio::time::timeout(Duration::from_secs(15), stream.next())
        .await
        .map_err(|_| anyhow!("délai d'attente dépassé"))?
        .ok_or_else(|| anyhow!("flux terminé"))??;
    match item {
        Message::Text(t) => Ok(serde_json::from_str(&t).context("trame illisible")?),
        other => bail!("trame inattendue : {other:?}"),
    }
}

async fn handle_frame(
    state: &std::sync::Arc<AppState>,
    ident: &Identity,
    config_dir: &Path,
    sink: &mut (impl SinkExt<Message> + Unpin),
    v: Value,
    last_announce: &mut Option<Instant>,
) -> Result<()> {
    let t = v.get("t").and_then(|t| t.as_str()).unwrap_or_default();
    match t {
        "pong" => {}
        "peer" => {
            info!(device = %v["device"], state = %v["state"], "état du pair");
        }
        "pair.join" => {
            info!(phone = %v["phone_name"], "demande d'appairage du téléphone reçue");
        }
        "pair.done" => {
            let peer = v["phone_id"].as_str().unwrap_or_default().to_string();
            let pk = v
                .get("phone_dh_pubkey")
                .and_then(|d| d.as_str())
                .filter(|d| !d.is_empty())
                .or_else(|| v["phone_pubkey"].as_str())
                .unwrap_or_default()
                .to_string();
            let k = ident.derive_session_key(&pk)?;
            store_pair(config_dir, &PairState { peer, peer_pubkey: pk, k: hex::encode(k) })?;
            *last_announce = None;
            info!("appairage terminé — clé de session E2E dérivée");
            println!("✔ téléphone appairé (le code n'est plus valide)");
        }
        "unpaired" => {
            let _ = std::fs::remove_file(pair_path(config_dir));
            warn!("paire dissoute par l'autre appareil");
        }
        "relay" => {
            let Some(from) = v.get("from").and_then(|f| f.as_str()) else { return Ok(()) };
            let Some(payload) = v.get("payload").and_then(|p| p.as_str()) else { return Ok(()) };
            let Some(pair) = load_pair(config_dir) else {
                let _ = sink.send(Message::Text(
                    json!({ "t": "error", "error": "aucun téléphone appairé" }).to_string(),
                )).await;
                return Ok(());
            };
            let k = hex::decode(&pair.k).map_err(|_| anyhow!("clé locale illisible"))?;
            let mut k32 = [0u8; 32];
            k32.copy_from_slice(&k);

            let inner = decrypt(&k32, payload).context("payload illisible")?;
            let Some(incoming) = Incoming::parse(&inner) else {
                warn!("frame applicative illisible depuis {from}");
                return Ok(());
            };
            let mut authed = true; // l'appairage relais fait foi
            let reply = dispatch(state, incoming, &mut authed).await;
            if let Some(r) = reply {
                let enc = encrypt(&k32, &r)?;
                sink.send(Message::Text(
                    json!({ "t": "relay", "to": from, "payload": enc }).to_string(),
                ))
                .await
                .map_err(|_| anyhow!("envoi WebSocket échoué"))?;
            }
        }
        "error" => warn!("relais : {}", v["error"]),
        other => tracing::debug!(t = other, "trame relais ignorée"),
    }
    Ok(())
}

// ------------------------------------------------------------------ helpers

fn b64(bytes: &[u8]) -> String {
    base64::engine::general_purpose::STANDARD.encode(bytes)
}

fn b64decode(s: &str) -> Result<Vec<u8>> {
    base64::engine::general_purpose::STANDARD
        .decode(s)
        .map_err(|e| anyhow!("base64 : {e}"))
}

fn hex_decode(s: &str) -> Result<Vec<u8>> {
    hex::decode(s.trim()).map_err(|e| anyhow!("hex : {e}"))
}
