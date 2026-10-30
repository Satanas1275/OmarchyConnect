//! Actions bureau pour Omarchy (Wayland) :
//!  - presse-papiers partagé en temps réel (`wl-paste` / `wl-copy`) ;
//!  - notifications du téléphone (`notify-send`) ;
//!  - entrée distante : souris via un périphérique `uinput` virtuel, clavier
//!    et texte via `wtype` (protocole virtual-keyboard de wlroots/Hyprland).
//!
//! Tous les outils sont appelés en sous-processus : aucun lien avec une
//! bibliothèque graphique, et le daemon reste utilisable sans session.

use std::io::Write;
use std::os::fd::AsRawFd;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use serde_json::{Map, Value};
use tokio::io::AsyncWriteExt;
use tokio::process::Command;
use tracing::{debug, warn};

use crate::protocol::frame;
use crate::server::AppState;

// --------------------------------------------------------------- presse-papiers

/// Surveille le presse-papiers du PC et diffuse les changements au téléphone.
pub async fn clipboard_watch(state: Arc<AppState>) {
    let mut seen = String::new();
    loop {
        if let Some(text) = read_clipboard().await {
            if !text.is_empty() && text != seen {
                seen = text.clone();
                let echoed = state
                    .last_clipboard
                    .lock()
                    .map(|c| *c == text)
                    .unwrap_or(false);
                if let Ok(mut cached) = state.last_clipboard.lock() {
                    *cached = text.clone();
                }
                if !echoed {
                    debug!(len = text.chars().count(), "presse-papiers PC -> téléphone");
                    state.broadcast(frame("clipboard", serde_json::json!({ "text": text })));
                }
            }
        }
        tokio::time::sleep(Duration::from_millis(1_200)).await;
    }
}

/// Lit le presse-papiers (texte). `None` si vide / indisponible.
async fn read_clipboard() -> Option<String> {
    // On ne lit que du texte : une image ou un blob binaire ne doit pas être
    // interprété comme une chaîne UTF-8 et diffusé au téléphone.
    let out = tokio::time::timeout(
        Duration::from_millis(900),
        Command::new("wl-paste")
            .arg("--no-newline")
            .arg("--type")
            .arg("text/plain;charset=utf-8")
            .output(),
    )
    .await
    .ok()?
    .ok()?;
    if !out.status.success() {
        return None;
    }
    Some(String::from_utf8_lossy(&out.stdout).into_owned())
}

/// Applique un presse-papiers reçu du téléphone (sans le renvoyer au PC).
pub async fn apply_clipboard(state: &AppState, text: &str) {
    if let Ok(mut cached) = state.last_clipboard.lock() {
        *cached = text.to_string();
    }
    let mut child = match Command::new("wl-copy")
        .arg("--type")
        .arg("text/plain;charset=utf-8")
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::null())
        .spawn()
    {
        Ok(c) => c,
        Err(e) => {
            warn!("wl-copy indisponible : {e}");
            return;
        }
    };
    if let Some(mut stdin) = child.stdin.take() {
        if let Err(e) = stdin.write_all(text.as_bytes()).await {
            warn!("wl-copy stdin : {e}");
        }
        let _ = stdin.shutdown().await;
    }
    match tokio::time::timeout(Duration::from_secs(3), child.wait()).await {
        Ok(Ok(s)) if s.success() => {
            debug!(len = text.chars().count(), "presse-papiers téléphone -> PC");
        }
        Ok(Ok(s)) => warn!("wl-copy : statut {s}"),
        Ok(Err(e)) => warn!("wl-copy : {e}"),
        Err(_) => {
            warn!("wl-copy : délai dépassé");
            let _ = child.kill().await;
        }
    }
}

// --------------------------------------------------------------- notifications

/// Affiche une notification du téléphone sur le bureau.
pub async fn notify(app: &str, title: &str, body: &str) {
    let app = if app.is_empty() {
        "OmarchyConnect"
    } else {
        app
    };
    let title = if title.is_empty() {
        "Téléphone"
    } else {
        title
    };
    let result = Command::new("notify-send")
        .arg("--app-name")
        .arg(app)
        .arg("--")
        .arg(title)
        .arg(body)
        .output()
        .await;
    match result {
        Ok(out) if !out.status.success() => {
            warn!(
                "notify-send : {}",
                String::from_utf8_lossy(&out.stderr).trim()
            );
        }
        Err(e) => warn!("notify-send indisponible : {e}"),
        _ => {}
    }
}

// ------------------------------------------------------------------ entrée

/// Traite une trame `input` : `kind` = move | click | scroll | text | key.
pub async fn input(fields: &Map<String, Value>) {
    let kind = fields.get("kind").and_then(|v| v.as_str()).unwrap_or("");
    match kind {
        "move" => {
            let dx = number(fields.get("dx"));
            let dy = number(fields.get("dy"));
            with_mouse(|m| m.move_by(dx, dy));
        }
        "scroll" => {
            let dy = number(fields.get("dy"));
            with_mouse(|m| m.scroll(dy));
        }
        "click" => {
            let button = fields
                .get("button")
                .and_then(|v| v.as_str())
                .unwrap_or("left");
            let code = match button {
                "right" => BTN_RIGHT,
                "middle" => BTN_MIDDLE,
                _ => BTN_LEFT,
            };
            with_mouse(|m| m.click(code));
        }
        "text" => {
            let text = fields.get("text").and_then(|v| v.as_str()).unwrap_or("");
            if !text.is_empty() {
                wtype(&[text.to_string()]).await;
            }
        }
        "key" => {
            let key = fields.get("key").and_then(|v| v.as_str()).unwrap_or("");
            let symbol = keysym(key);
            if symbol.is_empty() {
                return;
            }
            let mods: Vec<String> = fields
                .get("mods")
                .and_then(|v| v.as_array())
                .map(|a| {
                    a.iter()
                        .filter_map(|m| m.as_str())
                        .map(|m| keysym(m))
                        .filter(|s| !s.is_empty())
                        .collect()
                })
                .unwrap_or_default();
            let mut args = Vec::new();
            for m in &mods {
                args.push("-M".to_string());
                args.push(m.clone());
            }
            args.push("-k".to_string());
            args.push(symbol);
            for m in &mods {
                args.push("-m".to_string());
                args.push(m.clone());
            }
            wtype(&args).await;
        }
        other => debug!(kind = other, "input ignoré"),
    }
}

fn number(v: Option<&Value>) -> f64 {
    v.and_then(|v| v.as_f64()).unwrap_or(0.0)
}

/// Associe les noms envoyés par Android (akm) aux keysyms XKB.
fn keysym(key: &str) -> String {
    let mapped = match key {
        "ctrl_l" | "ctrl" | "Control_L" => "Control_L",
        "ctrl_r" => "Control_R",
        "alt_l" | "alt" | "Alt_L" => "Alt_L",
        "alt_r" => "Alt_R",
        "shift_l" | "shift" | "Shift_L" => "Shift_L",
        "shift_r" => "Shift_R",
        "super_l" | "super" | "Super_L" => "Super_L",
        "super_r" => "Super_R",
        "esc" | "Escape" => "Escape",
        "tab" | "Tab" => "Tab",
        "enter" | "Return" => "Return",
        "back" | "BackSpace" => "BackSpace",
        "delete" | "Delete" => "Delete",
        "space" | "Space" => "space",
        "arrow_left" | "Left" => "Left",
        "arrow_right" | "Right" => "Right",
        "arrow_up" | "Up" => "Up",
        "arrow_down" | "Down" => "Down",
        "home" | "Home" => "Home",
        "end" | "End" => "End",
        "page_up" | "Page_Up" => "Page_Up",
        "page_down" | "Page_Down" => "Page_Down",
        other => other,
    };
    mapped.to_string()
}

async fn wtype(args: &[String]) {
    let output = Command::new("wtype").args(args).output().await;
    match output {
        Ok(out) if !out.status.success() => {
            warn!("wtype : {}", String::from_utf8_lossy(&out.stderr).trim());
        }
        Err(e) => {
            // Repli pour les sessions sans virtual-keyboard (X11/autre).
            warn!("wtype indisponible ({e}), repli ydotool");
            let _ = Command::new("ydotool")
                .args(["type", "--"])
                .args(args)
                .output()
                .await;
        }
        _ => {}
    }
}

// ------------------------------------------------------- souris uinput

// Constantes noyau (linux/input-event-codes.h, linux/uinput.h).
const EV_SYN: u16 = 0x00;
const EV_KEY: u16 = 0x01;
const EV_REL: u16 = 0x02;
const SYN_REPORT: u16 = 0x00;
const REL_X: u16 = 0x00;
const REL_Y: u16 = 0x01;
const REL_WHEEL: u16 = 0x08;
const BTN_LEFT: u16 = 0x110;
const BTN_RIGHT: u16 = 0x111;
const BTN_MIDDLE: u16 = 0x112;

const UI_SET_EVBIT: libc::c_ulong = 0x4004_5564;
const UI_SET_KEYBIT: libc::c_ulong = 0x4004_5565;
const UI_SET_RELBIT: libc::c_ulong = 0x4004_5566;
const UI_DEV_SETUP: libc::c_ulong = 0x405c_5503;
const UI_DEV_CREATE: libc::c_ulong = 0x5501;

#[repr(C)]
struct InputId {
    bustype: u16,
    vendor: u16,
    product: u16,
    version: u16,
}

#[repr(C)]
struct UinputSetup {
    id: InputId,
    name: [u8; 80],
    ff_effects_max: u32,
}

#[repr(C)]
struct InputEvent {
    time: libc::timeval,
    kind: u16,
    code: u16,
    value: i32,
}

struct Mouse {
    file: std::fs::File,
    rem_x: f64,
    rem_y: f64,
    rem_wheel: f64,
}

static MOUSE: OnceLock<Mutex<Option<Mouse>>> = OnceLock::new();

fn with_mouse<F: FnOnce(&mut Mouse)>(f: F) {
    let mut guard = match MOUSE.get_or_init(|| Mutex::new(None)).lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    if guard.is_none() {
        match Mouse::open() {
            Ok(m) => {
                warn!("/dev/uinput ouvert : souris virtuelle OmarchyConnect prête");
                *guard = Some(m);
            }
            Err(e) => {
                warn!("souris virtuelle indisponible (/dev/uinput) : {e}");
                return;
            }
        }
    }
    if let Some(m) = guard.as_mut() {
        f(m);
    }
}

impl Mouse {
    fn open() -> std::io::Result<Mouse> {
        let file = std::fs::OpenOptions::new()
            .write(true)
            .open("/dev/uinput")?;
        let fd = file.as_raw_fd();
        unsafe {
            for (req, code) in [
                (UI_SET_EVBIT, EV_KEY as libc::c_ulong),
                (UI_SET_EVBIT, EV_REL as libc::c_ulong),
                (UI_SET_KEYBIT, BTN_LEFT as libc::c_ulong),
                (UI_SET_KEYBIT, BTN_RIGHT as libc::c_ulong),
                (UI_SET_KEYBIT, BTN_MIDDLE as libc::c_ulong),
                (UI_SET_RELBIT, REL_X as libc::c_ulong),
                (UI_SET_RELBIT, REL_Y as libc::c_ulong),
                (UI_SET_RELBIT, REL_WHEEL as libc::c_ulong),
            ] {
                if libc::ioctl(fd, req, code) < 0 {
                    return Err(std::io::Error::last_os_error());
                }
            }
            let mut setup = UinputSetup {
                id: InputId {
                    bustype: 0x03, // BUS_USB
                    vendor: 0x1209,
                    product: 0x0c0c,
                    version: 1,
                },
                name: [0u8; 80],
                ff_effects_max: 0,
            };
            let name = b"OmarchyConnect Remote";
            setup.name[..name.len()].copy_from_slice(name);
            if libc::ioctl(fd, UI_DEV_SETUP, &setup) < 0 {
                return Err(std::io::Error::last_os_error());
            }
            if libc::ioctl(fd, UI_DEV_CREATE) < 0 {
                return Err(std::io::Error::last_os_error());
            }
        }
        // Laisse le noyau/udev publier le périphérique.
        std::thread::sleep(Duration::from_millis(120));
        Ok(Mouse {
            file,
            rem_x: 0.0,
            rem_y: 0.0,
            rem_wheel: 0.0,
        })
    }

    fn emit(&mut self, kind: u16, code: u16, value: i32) {
        let ev = InputEvent {
            time: libc::timeval {
                tv_sec: 0,
                tv_usec: 0,
            },
            kind,
            code,
            value,
        };
        let bytes = unsafe {
            std::slice::from_raw_parts(
                &ev as *const InputEvent as *const u8,
                std::mem::size_of::<InputEvent>(),
            )
        };
        if let Err(e) = self.file.write_all(bytes) {
            warn!("uinput write : {e}");
        }
    }

    fn sync(&mut self) {
        self.emit(EV_SYN, SYN_REPORT, 0);
    }

    fn move_by(&mut self, dx: f64, dy: f64) {
        self.rem_x += dx;
        self.rem_y += dy;
        let mx = self.rem_x.trunc();
        let my = self.rem_y.trunc();
        if mx == 0.0 && my == 0.0 {
            return;
        }
        self.rem_x -= mx;
        self.rem_y -= my;
        // On borne chaque pas pour éviter les sauts aberrants.
        let mx = (mx as i32).clamp(-500, 500);
        let my = (my as i32).clamp(-500, 500);
        if mx != 0 {
            self.emit(EV_REL, REL_X, mx);
        }
        if my != 0 {
            self.emit(EV_REL, REL_Y, my);
        }
        self.sync();
    }

    fn scroll(&mut self, dy: f64) {
        self.rem_wheel += dy;
        let steps = self.rem_wheel.trunc();
        if steps == 0.0 {
            return;
        }
        self.rem_wheel -= steps;
        let steps = (steps as i32).clamp(-20, 20);
        if steps != 0 {
            self.emit(EV_REL, REL_WHEEL, steps);
            self.sync();
        }
    }

    fn click(&mut self, button: u16) {
        self.emit(EV_KEY, button, 1);
        self.sync();
        std::thread::sleep(Duration::from_millis(12));
        self.emit(EV_KEY, button, 0);
        self.sync();
    }
}
