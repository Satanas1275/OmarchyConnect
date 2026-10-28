//! Configuration du daemon : commandes jouables depuis le téléphone.
//!
//! `~/.config/omarchy-connect/commands.toml` :
//!
//! ```toml
//! [[commands]]
//! id = "lock"
//! name = "Lock session"
//! description = "Verrouille l'écran"
//! run = "loginctl lock-session"
//! danger = false
//! ```

use anyhow::{Context, Result};
use serde::Deserialize;

#[derive(Debug, Clone, Deserialize)]
pub struct Command {
    pub id: String,
    pub name: String,
    pub description: String,
    #[serde(default)]
    pub run: String,
    /// Affiché en rouge et précédé d'une confirmation côté téléphone.
    #[serde(default)]
    pub danger: bool,
}

#[derive(Debug, Deserialize)]
pub struct File {
    #[serde(default)]
    pub commands: Vec<Command>,
}

pub fn default_toml() -> &'static str {
    r#"
[[commands]]
id = "lock"
name = "Lock session"
description = "loginctl lock-session"
run = "loginctl lock-session"

[[commands]]
id = "screenshot"
name = "Screenshot"
description = "Capture l'écran entier dans ~/Pictures"
run = "grim ~/Pictures/shot-$(date +%s).png"

[[commands]]
id = "volume_up"
name = "Volume +5%"
description = "wpctl set-volume @DEFAULT_AUDIO_SINK@ 5%+"
run = "wpctl set-volume @DEFAULT_AUDIO_SINK@ 5%+"

[[commands]]
id = "mute"
name = "Toggle mute"
description = "Coupe ou rétablit l'audio"
run = "wpctl set-mute @DEFAULT_AUDIO_SINK@ toggle"

[[commands]]
id = "browser"
name = "Open browser"
description = "Ouvre Firefox"
run = "firefox >/dev/null 2>&1 &"

[[commands]]
id = "shutdown"
name = "Shutdown"
description = "Éteint le PC"
run = "systemctl poweroff"
danger = true
"#
}

/// Charge les commandes (fichier fourni, sinon config XDG, sinon défaut).
pub fn load(path: Option<&str>) -> Result<Vec<Command>> {
    let raw = match path {
        Some(p) => std::fs::read_to_string(p)
            .with_context(|| format!("lecture de {p}"))?,
        None => {
            let xdg = std::env::var("XDG_CONFIG_HOME")
                .map(std::path::PathBuf::from)
                .or_else(|_| std::env::var("HOME").map(|h| std::path::PathBuf::from(h).join(".config")))
                .ok()
                .map(|d| d.join("omarchy-connect/commands.toml"));
            match xdg {
                Some(p) if p.exists() => std::fs::read_to_string(p)?,
                _ => default_toml().to_string(),
            }
        }
    };
    let parsed: File = toml::from_str(&raw).context("commands.toml invalide")?;
    if parsed.commands.is_empty() {
        anyhow::bail!("aucune commande définie");
    }
    Ok(parsed.commands)
}
