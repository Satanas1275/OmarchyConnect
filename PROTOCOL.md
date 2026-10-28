# OmarchyConnect — protocole v1

Canal : **WebSocket JSON** sur `wss://<domaine>/ws` (TLS obligatoire, cleartext désactivé
côté Android) + **HTTP POST** pour le contenu des fichiers.

Toutes les trames ont la forme :

```json
{ "v": 1, "t": "<type>", ... }
```

`v` est la version de protocole, `t` le type de trame. Tout le reste dépend du type.

## Sécurité

1. **Identité du téléphone** : clé EC P-256 générée dans le Keystore Android
   (clé privée non exportable), envoyée au pairage en SPKI DER base64.
2. **Appairage** : le PC affiche un code à 6-8 caractères (valable ~30 s).
   Le téléphone envoie `pair.request` avec ce code. Le PC vérifie, puis renvoie
   un `token` aléatoire (32 octets, base64url) stocké côté téléphone dans les
   préférences chiffrées (AES256-GCM via Keystore).
3. **Reconnexion** : `auth` avec le jeton, en trame (jamais dans l'URL → pas
   de fuite dans les logs / proxys).
4. **Empreinte** : le PC annonce l'empreinte SHA-256 de sa clé (`hello.fp`),
   le téléphone la compare à celle mémorisée au pairage (`pc_fp`) et ferme la
   socket en `1008` en cas de divergence (détection MITM).
5. Pas de cleartext, pas de télémétrie, `allowBackup=false`.

## Trames téléphone -> PC

| t | champs | rôle |
|---|--------|------|
| `pair.request` | `device`, `deviceId`, `name`, `pubKey`, `code` | appairage |
| `auth` | `token` | ré-authentification |
| `theme.get` | – | demande la palette Omarchy |
| `commands.list` | – | recharge les commandes |
| `command.run` | `id` | exécute une commande **sur le PC** |
| `clipboard` | `text` | presse-papiers temps réel (tel -> PC) |
| `notification` | `pkg`, `app`, `title`, `text`, `at` | notif du téléphone -> PC |
| `input` | `kind` (`move`/`scroll`/`click`/`key`/`text`), `dx`, `dy`, `button`, `key`, `text`, `mods[]` | souris + clavier distant |
| `file.offer` | `name`, `size` | annonce (le contenu part par `POST /api/files`) |
| `ping` | – | mesure RTT |

### `input` (détails)

- `move` : déplacement relatif en pixels (`dx`, `dy`), max 40 evt/s
- `scroll` : `dy` en clics de molette
- `click` : `button` = `left` | `right` | `middle`
- `key` : `key` = nom XKB : `Escape`, `Tab`, `Return`, `BackSpace`, `Left`, …
- `text` : insertion brute de texte (clavier virtuel)

## Trames PC -> téléphone

| t | champs |
|---|--------|
| `hello` | `pc`, `user`, `since`, `fp` |
| `pair.ok` | `token`, `user`, `since` |
| `auth.ok` | `user` |
| `theme` | `name`, `c` : `{bg, surface, surfaceAlt, border, borderAccent, accent, primary, text, muted, danger}` |
| `commands` | `items[]` : `{id, name, description, danger}` |
| `command.result` | `id`, `name`, `ok`, `out` |
| `clipboard` | `text` (PC -> tel) |
| `file.offer` | `id`, `name`, `url`, `size` |
| `error` | `code`, `message` |
| `pong` | – |

Les couleurs du thème sont des hex (`#rrggbb` ou `rrggbb`) : le PC les lit dans
la config Omarchy (`~/.config/omarchy/*`) pour que le téléphone reprenne le même
thème.

## HTTP

| méthode | chemin | corps / entêtes |
|---|--------|-----------------|
| `POST` | `/api/files` | multipart form (`name`, `file`), `Authorization: Bearer <token>` |
| `GET` | `/api/theme` | identique, alternative REST pour la palette |
| `GET` | `/healthz` | sonde, sans auth (ne renvoie aucune donnée) |

Les fichiers venant du PC sont poussés au téléphone via `file.offer` +
`GET <url>` (le téléphone ajoute le même header d'autorisation, puis enregistre
le contenu dans `Download/`).
