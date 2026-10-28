//! Téléphone simulé : valide tout le protocole relais sans Android.
//!
//! Couvre : appairage HTTP (code → ECDH → finalize), auth WS par signature
//! ECDSA d'un nonce, relay E2E (AES-256-GCM via HKDF d'ECDH) et traitement
//! par le daemon PC.
//!
//! ```text
//! cargo run --example fake_phone -- <code_affiche_par_le_daemon>
//! ```

use anyhow::{bail, Context, Result};
use base64::Engine as _;
use futures_util::{SinkExt, StreamExt};
use hkdf::Hkdf;
use p256::ecdsa::signature::Signer;
use p256::ecdsa::{Signature, SigningKey};
use p256::elliptic_curve::sec1::ToEncodedPoint;
use p256::{PublicKey, SecretKey};
use rand::Rng;
use serde_json::{json, Value};
use sha2::Sha256;
use tokio_tungstenite::tungstenite::Message;

const BASE: &str = "http://127.0.0.1:6716";

#[tokio::main]
async fn main() -> Result<()> {
    let code = std::env::args()
        .nth(1)
        .context("usage : fake_phone <code d'appairage>")?;
    let http = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(10))
        .build()?;

    // Identité téléphone éphémère (comme un appairage neuf côté Android).
    let mut rng = rand::thread_rng();
    let phone_id: String = (0..16).map(|_| format!("{:02x}", rng.gen::<u8>())).collect();
    let signing = SigningKey::random(&mut rng);
    let sk = SecretKey::from_slice(&signing.to_bytes())?;
    let phone_pub = b64(sk.public_key().to_encoded_point(false).as_bytes());
    println!("phone_id  = {phone_id}");

    // 1. Appairage : entre le code affiché par le daemon PC.
    let v: Value = post(
        &http,
        "/api/pair/join",
        &json!({
            "code": code.trim(),
            "phone_id": phone_id,
            "phone_pubkey": phone_pub,
            "phone_name": "FakePhone",
        }),
    )
    .await?;
    let pc_id = v["pc_id"].as_str().context("pc_id manquant")?.to_string();
    let pc_pub = v
        .get("pc_dh_pubkey")
        .and_then(|d| d.as_str())
        .filter(|d| !d.is_empty())
        .or_else(|| v["pc_pubkey"].as_str())
        .context("pc_pubkey manquant")?
        .to_string();
    println!("pc        = {} ({})", v["pc_name"].as_str().unwrap_or("?"), pc_id);

    // 2. Clé de session E2E = HKDF(ECDH(...)).
    let k = derive(&signing, &pc_pub)?;
    println!("K         = {}…", hex::encode(&k[..6]));

    // 3. Finalisation : le serveur enregistre le téléphone et crée la paire.
    let v: Value = post(&http, "/api/pair/finalize", &json!({ "phone_id": phone_id })).await?;
    if v["pc_id"] != json!(pc_id) {
        bail!("finalize incohérent : {v}");
    }
    println!("✔ paire créée sur le relais");

    // 4. WebSocket + authentification par signature du nonce.
    let url = format!("ws://127.0.0.1:6716/ws?device={phone_id}");
    let (ws, _) = tokio_tungstenite::connect_async(&url).await?;
    let (mut sink, mut stream) = ws.split();
    let ch = recv(&mut stream).await?;
    if ch["t"] != "auth.challenge" {
        bail!("challenge attendu : {ch}");
    }
    let nonce = ch["nonce"].as_str().unwrap_or_default().to_string();
    let sig = sign(&signing, &nonce)?;
    sink.send(Message::Text(
        json!({ "t": "auth", "nonce": nonce, "sig": sig }).to_string(),
    ))
    .await?;
    let ok = recv(&mut stream).await?;
    if ok["t"] != "auth.ok" {
        bail!("auth refusée : {ok}");
    }
    println!("✔ WS authentifiée (clé privée jamais transmise)");

    // 5. Frames applicatives chiffrées de bout en bout.
    for inner in [
        json!({ "v": 1, "t": "theme.get" }),
        json!({ "v": 1, "t": "commands.list" }),
        json!({ "v": 1, "t": "command.run", "id": "hello" }),
    ] {
        let payload = encrypt(&k, &inner.to_string())?;
        sink.send(Message::Text(
            json!({ "t": "relay", "to": pc_id, "payload": payload }).to_string(),
        ))
        .await?;
        loop {
            let reply = recv(&mut stream).await?;
            match reply["t"].as_str() {
                Some("relay") => {
                    let p = reply["payload"].as_str().unwrap_or_default();
                    println!("◄ {}", decrypt(&k, p)?);
                    break;
                }
                Some(other) => println!("(trame : {other}) {reply}"),
                None => break,
            }
        }
    }

    println!("✔ protocole complet validé (pairing + auth + E2E + dispatch)");
    Ok(())
}

// ------------------------------------------------------------------ helpers

async fn post(http: &reqwest::Client, path: &str, body: &Value) -> Result<Value> {
    let resp = http.post(format!("{BASE}{path}")).json(body).send().await?;
    let status = resp.status();
    let text = resp.text().await.unwrap_or_default();
    if !status.is_success() {
        bail!("{path} → {status} : {text}");
    }
    Ok(serde_json::from_str(&text)?)
}

async fn recv(
    stream: &mut (impl StreamExt<Item = Result<Message, tokio_tungstenite::tungstenite::Error>>
              + Unpin),
) -> Result<Value> {
    let item = tokio::time::timeout(std::time::Duration::from_secs(15), stream.next())
        .await
        .context("délai dépassé")?
        .context("flux terminé")??;
    match item {
        Message::Text(t) => Ok(serde_json::from_str(&t)?),
        other => bail!("trame inattendue : {other:?}"),
    }
}

fn sign(signing: &SigningKey, msg: &str) -> Result<String> {
    let sig: Signature = signing.sign(msg.as_bytes());
    Ok(b64(sig.to_der().as_bytes()))
}

fn derive(signing: &SigningKey, peer_b64: &str) -> Result<[u8; 32]> {
    let peer = PublicKey::from_sec1_bytes(&b64d(peer_b64)?)?;
    let shared = p256::ecdh::diffie_hellman(signing.as_nonzero_scalar(), peer.as_affine());
    let hk = Hkdf::<Sha256>::new(Some(b"omarchy-connect-v1"), shared.raw_secret_bytes());
    let mut okm = [0u8; 32];
    hk.expand(b"payload-key-aes256", &mut okm)
        .map_err(|_| anyhow::anyhow!("HKDF"))?;
    Ok(okm)
}

fn encrypt(k: &[u8; 32], pt: &str) -> Result<String> {
    use aes_gcm::aead::{Aead, KeyInit};
    use aes_gcm::{Aes256Gcm, Key, Nonce};
    let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(k));
    let mut iv = [0u8; 12];
    rand::thread_rng().fill(&mut iv);
    let ct = cipher
        .encrypt(Nonce::from_slice(&iv), pt.as_bytes())
        .map_err(|_| anyhow::anyhow!("chiffrement"))?;
    let mut out = iv.to_vec();
    out.extend_from_slice(&ct);
    Ok(b64(&out))
}

fn decrypt(k: &[u8; 32], payload: &str) -> Result<String> {
    use aes_gcm::aead::{Aead, KeyInit};
    use aes_gcm::{Aes256Gcm, Key, Nonce};
    let data = b64d(payload)?;
    let (iv, ct) = data.split_at(12);
    let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(k));
    let pt = cipher
        .decrypt(Nonce::from_slice(iv), ct)
        .map_err(|_| anyhow::anyhow!("déchiffrement"))?;
    Ok(String::from_utf8(pt)?)
}

fn b64(bytes: &[u8]) -> String {
    base64::engine::general_purpose::STANDARD.encode(bytes)
}

fn b64d(s: &str) -> Result<Vec<u8>> {
    Ok(base64::engine::general_purpose::STANDARD.decode(s)?)
}
