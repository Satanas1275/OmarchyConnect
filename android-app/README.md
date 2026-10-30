# OmarchyConnect — app Android (Kotlin / Jetpack Compose)

L'app téléphone : notifications → PC, fichiers, presse-papiers temps réel,
commandes jouables, et le téléphone utilisé comme souris + clavier.

## Style

Fidèle à Omarchy : fond sombre, **contours colorés 1dp**, **aucun arrondi**
(angles droits partout, y compris avatar et pastille d'état), police
**JetBrains Mono** partout, accent menthe. Les couleurs sont **synchronisées avec
le thème Omarchy du PC** (trame `theme`).

## Écrans

| écran | rôle |
|-------|------|
| Welcome | présentation + `Get started` |
| Pair | endpoint relais + code d'appairage, ou **Bluetooth seul** sans compte |
| Home | état du lien, actions rapides, journal `activity` façon terminal |
| Commands | boutons = commandes définies **sur le PC** + sortie de dernière commande |
| Remote | pavé tactile (glisser/tap/appui long) + clavier |
| Settings | endpoint, Bluetooth, clipboard, notifications, thème, input distant |
| Profile | initiales, device id, empreinte de clé, déconnexion |

## Architecture

```
core/
  Prefs.kt            préférences chiffrées (EncryptedSharedPreferences)
  Crypto.kt           HKDF-SHA256, AES-256-GCM, base64/hex
  Protocol.kt         schéma des trames (lire ../PROTOCOL.md)
  DeviceIdentity.kt   clés EC P-256 Keystore (auth + ECDH), SEC1, sessionKey()
  Link.kt             relais WSS + BT RFCOMM, pairing, E2E, fichiers, journal
  OcTheme.kt          palette reçue du PC (fallback : défaut Omarchy)
  Models.kt           Command / LogLine / CommandResult / LinkStatus
service/
  LinkService.kt      service de premier plan : lien + presse-papiers temps réel
  NotificationForwarder.kt  NotificationListenerService → PC
ui/
  theme/Theme.kt      thème Compose (JetBrains Mono, dark, formes carrées)
  components/Oc.kt    Panel, OcButton, OcTextField, OcToggle, Chip, LogView, Avatar
  screens/*           les 7 écrans
  App.kt              navigation + barre du bas (Home/Commands/Remote/Settings/Profile)
```

## Transports et appairage

1. **Relais** (par défaut) : URL `https://omarchy-connect.rocknite-studio.com`,
   code à 8 caractères affiché par le daemon PC. Le téléphone rejoint la paire,
   dérive la clé ECDH, puis parle en AES-256-GCM à travers le relais.
2. **Bluetooth** : appairer le PC dans les réglages Bluetooth d'Android, puis
   « Continue without an account ». L'app ouvre une socket RFCOMM sur l'UUID SPP
   (`00001101-…`), échange `bt.hello` / `bt.accept` et dérive la même clé E2E.
3. **Secours automatique** : si le relais est injoignable et qu'un appareil
   Bluetooth est appairé, le lien bascule seul sur RFCOMM.

La clé privée ne quitte jamais le Keystore. Les clés publiques seules sont
envoyées au relais ; l'appairage est signé (ECDSA/SHA-256) sur un nonce à usage
unique.

## Build

```bash
cd android-app
./gradlew assembleDebug      # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease
```

JDK 17+ requis ; JDK 21 est recommandé (JDK 25 casse Gradle 8.9 / Kotlin 1.9.24).
Le SDK Android 34 doit être installé (`ANDROID_HOME` ou `local.properties`).

## Après l'installation

1. **Get started** → endpoint + code affiché par `omarchy-connect` sur le PC.
2. Settings → autoriser l'accès aux notifications (Android 12+ : permissions
   Bluetooth).
3. Le service de premier plan maintient le lien et synchronise le presse-papiers.

## Sécurité

- `usesCleartextTraffic=false` ; exceptions limitées à `10.0.2.2`, `127.0.0.1`
  et `localhost` pour le développement.
- Clés EC P-256 dans le Keystore Android (jamais exportées) ; clé de session et
  état dans `EncryptedSharedPreferences`.
- `allowBackup=false` : rien ne sort via ADB backup.
- Chiffrement E2E AES-256-GCM ; le relais ne peut pas lire les payloads.
- Aucun SDK tiers, aucune télémétrie.
