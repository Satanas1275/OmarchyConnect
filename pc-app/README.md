# omarchy-connect — daemon OmarchyConnect (Arch / Rust)

Le côté PC : appairage, transport relais E2E, Bluetooth SPP, commandes locales,
presse-papiers, notifications, entrée distante et réception de fichiers.

```bash
cargo build --release
./target/release/omarchy-connect            # relais public + Bluetooth
./target/release/omarchy-connect --no-bt    # sans Bluetooth
./target/release/omarchy-connect --no-relay # LAN direct uniquement
```

Au démarrage :

```
┌───────────────────────────────────────────────┐
│  OmarchyConnect — omarchy desktop daemon     │
├───────────────────────────────────────────────┤
│  listen    ws://127.0.0.1:8765                │
│  relais    https://omarchy-connect.rock…      │
│  lan code              2Q7QZEBD               │
│  bluetooth SPP / RFCOMM (canal 3)             │
│  files     /home/you/Downloads                │
│  commands  6                                  │
└───────────────────────────────────────────────┘
```

Le **code d'appairage relais** s'affiche ensuite séparément (8 caractères,
usage unique, 180 s). En mode Bluetooth, aucun code n'est nécessaire : appairer
le PC dans les réglages Android, puis « Continue without an account ».

## Transport

| mode | quand | sécurité |
|---|---|---|
| relais `wss://omarchy-connect.rocknite-studio.com` | par défaut | ECDH P-256 + AES-256-GCM, relais aveugle |
| Bluetooth RFCOMM/SPP | pas de réseau / sans compte | lien BT + E2E identique |
| LAN direct `ws://<pc>:8765` | développement | jeton local |

L'identité (clé EC privée) vit dans `~/.config/omarchy-connect/relay.json` en
`0600`. Le serveur ne reçoit que la clé publique.

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

`danger = true` → bouton rouge côté téléphone. Le téléphone n'envoie qu'un
identifiant : le PC décide de ce qui est exécutable.

## Fonctions bureau

| fonction | outil | notes |
|---|---|---|
| presse-papiers PC → tel | `wl-paste` (sondage 1,2 s) | évite les boucles grâce au dernier texte connu |
| presse-papiers tel → PC | `wl-copy` | type `text/plain;charset=utf-8` |
| notifications | `notify-send` | `app`, `title`, `text` |
| clavier / texte | `wtype` (virtual-keyboard) | repli `ydotool` |
| souris | `/dev/uinput` | périphérique virtuel « OmarchyConnect Remote » |

L'utilisateur doit pouvoir écrire sur `/dev/uinput` (ACL udev ou groupe
`input`). Sans cette permission, le clavier continue de fonctionner mais les
déplacements/clics souris sont désactivés (avertissement au premier usage).

## Endpoints locaux

| méthode | chemin | rôle |
|---|---|---|
| `GET` | `/ws` | protocole direct (JSON, voir `../PROTOCOL.md`) |
| `POST` | `/api/files` | réception multipart historique (Bearer) |
| `GET` | `/healthz` | sonde anonyme |

Les fichiers envoyés par le téléphone arrivent désormais par blocs E2E
(`file.begin` / `file.chunk` / `file.end`) puis sont écrits dans `upload_dir`.

## Options

| option | défaut | rôle |
|---|---|---|
| `--bind` | `127.0.0.1` | adresse d'écoute du serveur local |
| `--port` | `8765` | port du serveur local |
| `--relay` | `https://omarchy-connect.rocknite-studio.com` | URL du relais (local en dev : `http://127.0.0.1:6716`) |
| `--upload-dir` | `~/Downloads` | destination des fichiers |
| `--config` | XDG | chemin de `commands.toml` |
| `--no-relay` | – | désactive le client relais |
| `--no-bt` | – | désactive le profil SPP |

## Sécurité

- Clé privée EC P-256 générée localement, jamais transmise.
- Signature ECDSA/SHA-256 des nonces à usage unique (60 s).
- Chiffrement E2E AES-256-GCM ; le relais ne voit que des blobs opaques.
- Aucune télémétrie, aucune exécution arbitraire : seules les commandes de
  `commands.toml` sont lancées.
