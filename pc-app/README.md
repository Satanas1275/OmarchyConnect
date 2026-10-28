# omarchy-connect — daemon OmarchyConnect (Arch / Rust)

Le côté PC : écoute le téléphone, affiche le code d'appairage, exécute les
commandes déclarées localement et reçoit les fichiers.

```bash
cargo build --release
./target/release/omarchy-connect --port 8765
```

Au démarrage, un code d'appairage est affiché (usage unique, expire après
utilisation) :

```
┌───────────────────────────────────────────────┐
│  OmarchyConnect — omarchy desktop daemon     │
├───────────────────────────────────────────────┤
│  listen    ws://127.0.0.1:8765                │
│  pair code            2Q7QZEBD               │
│  files     /home/you/Downloads               │
│  commands  6                                 │
└───────────────────────────────────────────────┘
```

Entrer ce code + le domaine dans l'onglet **Pair** de l'app Android.

## Commandes

`~/.config/omarchy-connect/commands.toml` (sinon les exemples intégrés) :

```toml
[[commands]]
id = "lock"
name = "Lock session"
description = "loginctl lock-session"
run = "loginctl lock-session"
danger = false
```

`danger = true` → bouton rouge `run!` côté téléphone (rien d'autre : le PC
décide de ce qui s'exécute, le téléphone n'envoie que l'id).

## Endpoints

| méthode | chemin | rôle |
|---|---|---|
| `GET` | `/ws` | protocole (JSON, voir `../PROTOCOL.md`) |
| `POST` | `/api/files` | réception multipart (Bearer) |
| `GET` | `/healthz` | sonde anonyme |

## Plan de travail

- [ ] **TLS direct** : rustls + cert auto (ou Caddy/nginx devant) et
      `fingerprint = SHA-256(SPKI)` annoncé dans `hello.fp`
      (aujourd'hui : `identity` persistant dans `~/.config/omarchy-connect/`)
- [ ] **Input distant** : branchez `enigo` (X11/Wayland) sur la trame `input`
      (`move`/`scroll`/`click`/`key`/`text`)
- [ ] **Presse-papiers** : `arboard` sur `clipboard` + watcher d'émission
- [ ] **Notifications bureau** : `notify-rust` / KDE DBus
- [ ] **Bluetooth** : le protocole est transport-agnostic ; ajouter un
      transport RFCOMM/LE en parallèle du WebSocket (discovery + pairage)
- [ ] **Thème réel** : lire `~/.config/omarchy/wm` + palette du terminal au
      lieu de la palette par défaut
- [ ] **Reverse shell chiffré** : non, et jamais — le PC ne fait tourner que les
      commandes explicitement déclarées dans `commands.toml`
