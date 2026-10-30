# OmarchyConnect

KDE Connect, mais pour **Omarchy** : ton téléphone (Android/Kotlin) pilote ton PC
(Arch + Omarchy, daemon Rust) — notifications, fichiers, presse-papiers en temps
réel, commandes jouables et souris/clavier à distance, sur un canal unique
chiffré de bout en bout.

```
├── android-app/     app Android (Kotlin, Jetpack Compose)
├── pc-app/          daemon Rust pour Arch/Omarchy
├── server/          relais central Rust (WebSocket + appairage)
├── PROTOCOL.md      schéma des trames partagé par les trois
└── .github/         CI (build APK à chaque push sur dev/android)
```

## Fonctionnalités

- **Notifications** du téléphone → bureau (NotificationListenerService)
- **Fichiers** téléphone → PC par blocs chiffrés E2E (`file.begin/chunk/end`)
- **Presse-papiers temps réel** dans les deux sens (`wl-paste` / `wl-copy`)
- **Commandes** déclarées sur le PC, exécutées **sur le PC** (identifiant seul)
- **Souris + clavier** distants : `uinput` pour la souris, `wtype` pour le clavier
- **Thème synchronisé** avec Omarchy (couleurs poussées par le PC)
- **Transports** :
  - relais internet `wss://omarchy-connect.rocknite-studio.com` (le serveur ne
    voit que des blobs chiffrés) ;
  - **Bluetooth RFCOMM/SPP** en secours automatique, utilisable sans compte ni
    serveur (« Continue without an account ») ;
  - LAN direct `ws://<pc>:8765` pour le développement.

## Modèle de sécurité

- Chaque appareil a une clé **EC P-256** générée localement ; la clé privée n'est
  **jamais** transmise (Keystore Android / `relay.json` en `0600`).
- Le serveur ne stocke que les clés **publiques** SEC1 et ne connaît que la
  topologie « qui est appairé avec qui ».
- Appairage par **code à 8 caractères**, usage unique, TTL 180 s, puis
  **ECDH P-256 + HKDF-SHA256** → clé AES-256-GCM connue des deux appareils seuls.
- Requêtes sensibles signées **ECDSA/SHA-256** sur un nonce à usage unique (60 s).
- `allowBackup=false`, cleartext désactivé (sauf loopback/émulateur), aucune
  télémétrie.

Détails complets : [PROTOCOL.md](PROTOCOL.md).

## Style

Fidèle à Omarchy : fond sombre, **contours colorés**, **aucun arrondi** (angles
droits partout, y compris avatar et pastille d'état), **JetBrains Mono**,
accent menthe, rendu « terminal » pour le journal et les sorties de commandes.

## Build

```bash
# Relais (Rust)
cd server && cargo build --release

# Daemon PC (Rust)
cd pc-app && cargo build --release

# Android (JDK 17+ ; JDK 21 recommandé)
cd android-app && ./gradlew assembleDebug
```

Voir [android-app/README.md](android-app/README.md),
[pc-app/README.md](pc-app/README.md) et [server/README.md](server/README.md)
(déploiement du relais derrière Caddy/nginx) pour la configuration.
