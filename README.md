# OmarchyConnect

KDE Connect, mais pour **Omarchy** : ton téléphone (Android/Kotlin) pilote ton PC
(Arch + Omarchy, daemon Rust) — notifications, fichiers, presse-papiers en temps
réel, commandes jouables et souris/clavier à distance, sur un canal unique
chiffré.

```
├── android-app/     app Android (Kotlin, Jetpack Compose)  ← priorité actuelle
├── pc-app/          daemon Rust pour Arch/Omarchy
├── PROTOCOL.md      schéma des trames partagé par les deux
└── .github/         CI (build APK à chaque push sur dev/android)
```

## Fonctionnalités

- **Notifications** du téléphone → bureau (NotificationListenerService côté Android)
- **Fichiers** dans les deux sens (partage Android → PC, `Download/` côté téléphone)
- **Presse-papiers temps réel** (option dans les réglages, service de premier plan)
- **Commandes** : déclarées sur le PC, affichées sur le téléphone (nom +
  description + bouton), exécutées **sur le PC**
- **Souris + clavier** distants : l'un, l'autre ou les deux en même temps
- **Thème synchronisé** avec Omarchy (couleurs poussées par le PC)
- **Transports** : Wi-Fi / réseau / Internet via ton domaine (`wss://`),
  Bluetooth prévu en couches basses (le protocole est indépendant du transport)

## Style

Fidèle à Omarchy : fond sombre, **contours colorés**, arrondis minimes (3-4 dp),
**JetBrains Mono** partout, accent menthe, rendu « terminal » pour le journal
d'activité et les sorties de commandes.

## Build

```bash
# Android (JDK 17+)
cd android-app && ./gradlew assembleDebug

# PC
cd pc-app && cargo build --release
```

## Sécurité

- appairage par **code à usage unique** (30 s), clé EC P-256 non exportable dans
  le Keystore Android, jeton de session chiffré au repos
- `wss://` uniquement (cleartext désactivé), empreinte du PC vérifiée à chaque
  connexion (détection de man-in-the-middle)
- `allowBackup=false`, aucune télémétrie, aucun SDK tiers
- détails : [PROTOCOL.md](PROTOCOL.md) et [android-app/README.md](android-app/README.md)
