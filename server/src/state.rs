//! État du relais OmarchyConnect.
//!
//! **Ce que le serveur stocke** :
//!  - les appareils : `device_id` (aléatoire 128 bits), rôle, nom et **clé publique** SEC1
//!  - les paires : « qui doit se co à qui »
//!  - rien d'autre : aucune clé privée, aucun secret, aucun payload en clair
//!    (les paquets relayés sont chiffrés de bout en bout entre le PC et le téléphone).
//!
//! Nonces : usage unique, 60 s — sert d'anti-rejeu pour les requêtes signées.
//! Codes d'appairage : 8 caractéros, usage unique, 180 s.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use anyhow::{bail, Result};
use rand::Rng;
use serde::{Deserialize, Serialize};

fn now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Role {
    Pc,
    Phone,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Device {
    pub id: String,
    pub role: Role,
    pub name: String,
    /// Clé publique d'authentification (SEC165 octets, base64).
    pub pubkey: String,
    /// Clé publique dédiée à l'ECDH (le PC n'en a qu'une seule → optionnel,
    /// égale à `pubkey`). Android utilise deux clés Keystore distinctes.
    #[serde(default)]
    pub dh_pubkey: Option<String>,
    pub created: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Pair {
    pub pc: String,
    pub phone: String,
    pub created: u64,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct Persisted {
    devices: HashMap<String, Device>,
    pairs: Vec<Pair>,
}

/// Demande d'appairage en attente de finalisation côté téléphone.
pub struct PendingJoin {
    pub pc_id: String,
    pub phone_pubkey: String,
    pub phone_dh_pubkey: String,
    pub phone_name: String,
    pub expires: Instant,
}

#[derive(Debug, Clone, Serialize)]
pub struct PendingInfo {
    pub phone_id: String,
    pub phone_pubkey: String,
    pub phone_dh_pubkey: String,
    pub phone_name: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct Status {
    pub paired: bool,
    pub peer: Option<String>,
    pub peer_pubkey: Option<String>,
    pub peer_dh_pubkey: Option<String>,
    pub pending: Option<PendingInfo>,
}

struct Code {
    pc_id: String,
    expires: Instant,
}

struct Nonce {
    device_id: String,
    expires: Instant,
}

pub struct Registry {
    path: PathBuf,
    data: Mutex<Persisted>,
    codes: Mutex<HashMap<String, Code>>,
    joins: Mutex<HashMap<String, PendingJoin>>,
    nonces: Mutex<HashMap<String, Nonce>>,
}

impl Registry {
    pub fn load(path: PathBuf) -> Result<Registry> {
        let data = if path.exists() {
            serde_json::from_str(&std::fs::read_to_string(&path)?)?
        } else {
            Persisted::default()
        };
        Ok(Registry {
            path,
            data: Mutex::new(data),
            codes: Mutex::new(HashMap::new()),
            joins: Mutex::new(HashMap::new()),
            nonces: Mutex::new(HashMap::new()),
        })
    }

    fn save_locked(&self, data: &Persisted) -> Result<()> {
        if let Some(parent) = self.path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let tmp = self.path.with_extension("tmp");
        std::fs::write(&tmp, serde_json::to_string_pretty(data)?)?;
        std::fs::rename(&tmp, &self.path)?;
        Ok(())
    }

    // ------------------------------------------------------------- appareils

    /// Enregistrement (TOFU) d'un appareil : le serveur ne reçoit que des clés **publiques**.
    pub fn register(
        &self,
        id: &str,
        role: Role,
        name: &str,
        pubkey: &str,
        dh_pubkey: Option<String>,
    ) -> Result<()> {
        if id.is_empty() || id.len() > 64 || id.contains(|c: char| !c.is_ascii_alphanumeric() && c != '-' && c != '_') {
            bail!("device_id invalide");
        }
        let mut data = self.data.lock().unwrap();
        if let Some(existing) = data.devices.get(id) {
            if existing.pubkey != pubkey {
                // Rotation de clé : refusée ici, doit être prouvée par la clé ancienne
                // (gérée par le routeur HTTP avant appel).
                bail!("clé publique différente : rotation non autorisée");
            }
        }
        data.devices.insert(
            id.to_string(),
            Device {
                id: id.to_string(),
                role,
                name: name.to_string(),
                pubkey: pubkey.to_string(),
                dh_pubkey: dh_pubkey.filter(|d| !d.is_empty()),
                created: now(),
            },
        );
        self.save_locked(&data)?;
        Ok(())
    }

    pub fn pubkey_of(&self, id: &str) -> Option<String> {
        self.data.lock().unwrap().devices.get(id).map(|d| d.pubkey.clone())
    }

    /// Clé publique à utiliser pour l'ECDH avec `id` (défaut : clé d'auth).
    pub fn dh_pubkey_of(&self, id: &str) -> Option<String> {
        let data = self.data.lock().unwrap();
        let d = data.devices.get(id)?;
        Some(d.dh_pubkey.clone().unwrap_or_else(|| d.pubkey.clone()))
    }

    /// « Qui doit se co à qui » : l'appairé de `id`.
    pub fn peer_of(&self, id: &str) -> Option<String> {
        let data = self.data.lock().unwrap();
        data.pairs
            .iter()
            .find(|p| p.pc == id || p.phone == id)
            .map(|p| if p.pc == id { p.phone.clone() } else { p.pc.clone() })
    }

    pub fn pair(&self, pc: &str, phone: &str) -> Result<()> {
        let mut data = self.data.lock().unwrap();
        if data.pairs.iter().any(|p| p.pc == pc && p.phone == phone) {
            return Ok(());
        }
        data.pairs.push(Pair {
            pc: pc.to_string(),
            phone: phone.to_string(),
            created: now(),
        });
        self.save_locked(&data)?;
        Ok(())
    }

    pub fn unpair(&self, id: &str) -> Result<()> {
        let mut data = self.data.lock().unwrap();
        data.pairs.retain(|p| p.pc != id && p.phone != id);
        self.save_locked(&data)?;
        Ok(())
    }

    /// État pour un appareil : paire, clés du pair, demande d'appairage en attente.
    pub fn status(&self, id: &str) -> Status {
        let peer = {
            let data = self.data.lock().unwrap();
            data.pairs
                .iter()
                .find(|p| p.pc == id || p.phone == id)
                .map(|p| if p.pc == id { p.phone.clone() } else { p.pc.clone() })
        };
        let (peer_pubkey, peer_dh_pubkey) = match &peer {
            Some(p) => {
                let data = self.data.lock().unwrap();
                match data.devices.get(p) {
                    Some(d) => (
                        Some(d.pubkey.clone()),
                        Some(d.dh_pubkey.clone().unwrap_or_else(|| d.pubkey.clone())),
                    ),
                    None => (None, None),
                }
            }
            None => (None, None),
        };
        let pending = {
            let joins = self.joins.lock().unwrap();
            joins
                .iter()
                .find(|(_, j)| j.pc_id == id && j.expires > Instant::now())
                .map(|(phone_id, j)| PendingInfo {
                    phone_id: phone_id.clone(),
                    phone_pubkey: j.phone_pubkey.clone(),
                    phone_dh_pubkey: j.phone_dh_pubkey.clone(),
                    phone_name: j.phone_name.clone(),
                })
        };
        Status {
            paired: peer.is_some(),
            peer,
            peer_pubkey,
            peer_dh_pubkey,
            pending,
        }
    }

    // --------------------------------------------------------------- nonces

    /// Émet un nonce à usage unique pour `device_id` (valable 60 s).
    pub fn new_nonce(&self, device_id: &str) -> String {
        let mut rng = rand::thread_rng();
        let nonce: String = (0..32).map(|_| format!("{:02x}", rng.gen::<u8>())).collect();
        let mut nonces = self.nonces.lock().unwrap();
        nonces.retain(|_, n| n.expires > Instant::now());
        nonces.insert(
            nonce.clone(),
            Nonce {
                device_id: device_id.to_string(),
                expires: Instant::now() + Duration::from_secs(60),
            },
        );
        nonce
    }

    /// Consomme le nonce (anti-rejeu) après l'avoir rattaché à `device_id`.
    pub fn consume_nonce(&self, device_id: &str, nonce: &str) -> Result<()> {
        let mut nonces = self.nonces.lock().unwrap();
        let Some(record) = nonces.remove(nonce) else {
            bail!("nonce inconnu ou déjà utilisé")
        };
        if record.device_id != device_id {
            bail!("nonce appartenant à un autre appareil")
        }
        if record.expires < Instant::now() {
            bail!("nonce expiré")
        }
        Ok(())
    }

    // --------------------------------------------------------------- pairing

    /// Le PC génère un code d'appairage (affiché en local).
    pub fn announce(&self, pc_id: &str) -> Result<String> {
        if self.pubkey_of(pc_id).is_none() {
            bail!("pc non enregistré");
        }
        let alphabet: &[u8] = b"ABCDEFGHJKMNPQRSTUVWXYZ23456789";
        let mut rng = rand::thread_rng();
        let code: String = (0..8)
            .map(|_| alphabet[rng.gen_range(0..alphabet.len())] as char)
            .collect();
        let mut codes = self.codes.lock().unwrap();
        codes.retain(|_, c| c.expires > Instant::now());
        codes.insert(
            code.clone(),
            Code {
                pc_id: pc_id.to_string(),
                expires: Instant::now() + Duration::from_secs(180),
            },
        );
        Ok(code)
    }

    /// Le téléphone entre le code : renvoie (pc_id, pc_name, pc_pubkey).
    /// Le code fait foi de consentement (usage unique, 180 s).
    pub fn join(
        &self,
        code: &str,
        phone_id: &str,
        phone_pubkey: &str,
        phone_dh_pubkey: Option<&str>,
        phone_name: &str,
    ) -> Result<(String, String, String)> {
        let key = code.trim().to_uppercase();
        let pc_id = {
            let mut codes = self.codes.lock().unwrap();
            codes.retain(|_, c| c.expires > Instant::now());
            let pc_id = match codes.iter().find(|(c, _)| **c == key).map(|(_, v)| v.pc_id.clone()) {
                Some(pc) => pc,
                None => bail!("code invalide ou expiré"),
            };
            codes.remove(&key);
            pc_id
        };

        let (pc_name, pc_pubkey) = {
            let data = self.data.lock().unwrap();
            let dev = data
                .devices
                .get(&pc_id)
                .ok_or_else(|| anyhow::anyhow!("pc non enregistré"))?;
            (dev.name.clone(), dev.pubkey.clone())
        };

        let phone_dh = phone_dh_pubkey
            .filter(|d| !d.is_empty())
            .unwrap_or(phone_pubkey)
            .to_string();

        let mut joins = self.joins.lock().unwrap();
        joins.retain(|_, j| j.expires > Instant::now());
        joins.insert(
            phone_id.to_string(),
            PendingJoin {
                pc_id: pc_id.clone(),
                phone_pubkey: phone_pubkey.to_string(),
                phone_dh_pubkey: phone_dh,
                phone_name: phone_name.to_string(),
                expires: Instant::now() + Duration::from_secs(180),
            },
        );
        drop(joins);

        Ok((pc_id, pc_name, pc_pubkey))
    }

    /// Le téléphone finalise : devient appareil enregistré + création de la paire.
    /// Renvoie (pc_id, pc_pubkey, phone_pubkey, phone_dh_pubkey).
    pub fn finalize(&self, phone_id: &str) -> Result<(String, String, String, String)> {
        let pending = {
            let mut joins = self.joins.lock().unwrap();
            let Some(j) = joins.remove(phone_id) else {
                bail!("aucune demande en attente")
            };
            if j.expires < Instant::now() {
                bail!("demande expirée")
            }
            j
        };

        self.register(
            phone_id,
            Role::Phone,
            &pending.phone_name,
            &pending.phone_pubkey,
            Some(pending.phone_dh_pubkey.clone()),
        )?;

        let pc_id = pending.pc_id.clone();
        let pc_pubkey = self.pubkey_of(&pc_id).unwrap_or_default();
        self.pair(&pc_id, phone_id)?;
        Ok((
            pc_id,
            pc_pubkey,
            pending.phone_pubkey,
            pending.phone_dh_pubkey,
        ))
    }
}
