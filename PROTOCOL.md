# OmarchyConnect — protocole v1

Trois transports, **un seul protocole applicatif** :

| transport | usage | sécurité |
|---|---|---|
| relais WebSocket `wss://<domaine>/ws` | internet, via le serveur central | E2E (le relais ne lit rien) |
| Bluetooth RFCOMM/SPP | « Continue without an account », sans serveur | lien déjà chiffré par l'appairage BT + E2E |
| WebSocket direct `ws://<pc>:8765/ws` | développement / LAN de confiance | jeton local (mode historique) |

Les trames applicatives sont **toujours** un objet JSON :

```json
{ "v": 1, "t": "<type>", ... }
```

## 1. Identités et cryptographie

- Chaque appareil génère une clé **EC P-256** localement. La clé privée ne
  quitte **jamais** l'appareil (Keystore Android / `relay.json` en `0600` côté PC).
- Le serveur ne stocke que les clés **publiques** SEC1 non compressées (65 octets,
  base64 standard).
- Les requêtes sensibles sont signées en **ECDSA / SHA-256** (DER base64) sur un
  nonce à usage unique (TTL 60 s).
- Clé de session E2E :

```text
shared = ECDH(privée_locale, publique_pair)          # P-256
K      = HKDF-SHA256(salt="omarchy-connect-v1",
                     info="payload-key-aes256", IKM=shared)   # 32 octets
```

- Chaque frame applicative est chiffrée en **AES-256-GCM** avec un IV aléatoire
  de 12 octets préfixé au chiffré, le tout en base64 standard :

```json
{ "t": "relay", "payload": "<base64(iv || ciphertext+tag)>" }
```

Le relais ne voit que `{t, from/to, payload}` : il ne peut ni lire ni modifier
le contenu sans être détecté (tag GCM).

## 2. Enregistrement et authentification (relais)

```text
POST /api/challenge        {"device_id"}                       -> {"nonce", "ttl":60}
POST /api/register         {"device_id","role","name",
                            "pubkey","dh_pubkey"?}             -> {"ok":true}
POST /api/pair/announce    {"device_id","nonce","sig"}         -> {"code","ttl":180}
POST /api/pair/join        {"code","phone_id","phone_pubkey",
                            "phone_dh_pubkey"?,"phone_name"}   -> {"pc_id","pc_name",
                                                                 "pc_pubkey","pc_dh_pubkey"}
POST /api/pair/finalize    {"phone_id"}                        -> {"pc_id","peer_pubkey",
                                                                 "peer_dh_pubkey"}
GET  /api/pair/status?device=<id>   (X-OC-Nonce / X-OC-Sig)    -> {"paired","peer","peer_pubkey",
                                                                 "peer_dh_pubkey","pending"}
POST /api/pair/unpair      {"device_id","nonce","sig"}         -> {"ok":true}
GET  /healthz                                                  -> {"ok":true,"v":1}
```

`role` = `"pc"` ou `"phone"`. Le code d'appairage fait 8 caractères, est à
**usage unique** et expire après **180 s**.

### Appairage

1. Le PC (`announce`) obtient un code et l'affiche.
2. Le téléphone (`join`) envoie le code + ses clés publiques ; le serveur répond
   avec `pc_dh_pubkey` et pousse `pair.join` au PC.
3. Les deux côtés calculent `K` par ECDH (aucune clé privée ne circule).
4. Le téléphone (`finalize`) crée la paire ; le serveur pousse `pair.done` au PC
   et enregistre le téléphone.

### WebSocket

`GET /ws?device=<device_id>` — le serveur envoie d'abord :

```json
{ "t": "auth.challenge", "nonce": "<nonce>" }
```

Le client répond :

```json
{ "t": "auth", "nonce": "<nonce>", "sig": "<ECDSA-DER-base64>" }
```

puis le serveur répond `{"t":"auth.ok"}` (ou `{"t":"error", ...}`).

Trames de contrôle du relais :

| t | sens | champs |
|---|---|---|
| `relay` | les deux | `from` (serveur→client), `to` (client→serveur), `payload` |
| `pair.join` | serveur→PC | `phone_id`, `phone_pubkey`, `phone_dh_pubkey`, `phone_name` |
| `pair.done` | serveur→PC | `phone_id`, `phone_pubkey`, `phone_dh_pubkey` |
| `peer` | serveur→client | `device`, `state` (`online` / `offline`) |
| `unpaired` | serveur→client | `by` |
| `ping` / `pong` | les deux | – (keepalive, en clair) |
| `error` | serveur→client | `error` |

## 3. Bluetooth (SPP / RFCOMM)

- UUID SPP : `00001101-0000-1000-8000-00805F9B34FB`, canal RFCOMM **3**.
- L'appareil doit d'abord être **appairé dans les réglages Android**.
- Le PC enregistre le profil auprès de BlueZ (agent `NoInputNoOutput`).

Poignée de main, une ligne JSON par message :

```json
{"t":"bt.hello","device_id":"…","name":"…","auth_pubkey":"…","dh_pubkey":"…"}
{"t":"bt.accept","pc":"…","pc_id":"…","pc_dh_pubkey":"…","fp":"…"}
```

Ensuite, toutes les frames applicatives passent chiffrées :

```json
{"t":"relay","payload":"<base64(iv || ciphertext+tag)>"}
```

`{"t":"ping"}` (clair) → `{"t":"pong"}` (clair). Le lien reste utilisable sans
compte et sans serveur.

## 4. Trames applicatives (chiffrées)

### Téléphone → PC

| t | champs | rôle |
|---|---|---|
| `theme.get` | – | demande la palette Omarchy |
| `commands.list` | – | recharge les commandes |
| `command.run` | `id` | exécute une commande **déclarée** sur le PC |
| `clipboard` | `text` | presse-papiers téléphone → PC (`wl-copy`) |
| `notification` | `pkg`, `app`, `title`, `text`, `at` | notification téléphone → bureau |
| `input` | `kind`, `dx`, `dy`, `button`, `key`, `text`, `mods[]` | souris + clavier distant |
| `file.begin` | `id`, `name` | début d'un transfert E2E |
| `file.chunk` | `id`, `data` | bloc base64 (relais aveugle) |
| `file.end` | `id`, `name`, `size` | fin → écriture dans `upload_dir` |
| `ping` | – | RTT applicatif |

`input` :

- `move` : déplacement relatif (`dx`, `dy`), limité à 40 evt/s côté téléphone ;
- `scroll` : `dy` en clics de molette ;
- `click` : `button` = `left` | `right` | `middle` ;
- `key` : nom XKB (`Escape`, `Tab`, `Return`, `BackSpace`, `Left`, `ctrl_l`, …) ;
- `text` : insertion brute via le clavier virtuel.

### PC → téléphone

| t | champs |
|---|---|
| `theme` | `name`, `c` : `{bg, surface, surfaceAlt, border, borderAccent, accent, primary, text, muted, danger}` |
| `commands` | `items[]` : `{id, name, description, danger}` |
| `command.result` | `id`, `name`, `ok`, `out` |
| `clipboard` | `text` (PC → téléphone, watcher `wl-paste`) |
| `file.result` | `id`, `name`, `ok`, `path`, `size` ou `error` |
| `error` | `code`, `message` |
| `pong` | – |

Un transfert `file.end` réussi répond par exemple :

```json
{"v":1,"t":"file.result","id":"…","name":"photo.jpg","ok":true,
 "path":"/home/you/Downloads/photo.jpg","size":184332}
```

Le nom est assaini (pas de séparateur de chemin) et un suffixe ` (n)` évite
d'écraser un fichier existant. Taille maximale : 1 Gio.

## 5. Notes de sécurité

- Cleartext HTTP/WS désactivé côté Android, sauf `10.0.2.2`, `127.0.0.1` et
  `localhost` (développement).
- `allowBackup=false`, clés dans le Keystore, préférences chiffrées.
- Le PC n'exécute que les commandes de `commands.toml` ; le téléphone n'envoie
  qu'un identifiant.
- Aucune télémétrie ; le relais ne journalise pas les payloads.
