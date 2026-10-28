# OmarchyConnect — app Android (Kotlin / Jetpack Compose)

L'app téléphone : notifications -> PC, fichiers, presse-papiers temps réel,
commandes jouables, et le téléphone utilisé comme souris + clavier.

## Style

Fidèle à Omarchy : fond sombre, **contours colorés 1dp**, quasiment pas
d'arrondis (3-4 dp), police **JetBrains Mono** partout, accents menthe.
Les couleurs sont **synchronisées avec le thème Omarchy du PC** (trame `theme`).

## Écrans

| écran | rôle |
|-------|------|
| Welcome | présentation + `Get started` |
| Pair | domaine + code d'appairage (ou démo sans compte) |
| Home | état du lien, actions rapides, journal `activity` façon terminal |
| Commands | boutons = commandes définies **sur le PC** + sortie de dernière commande |
| Remote | pavé tactile (glisser/tap/appui long) + clavier, `mouse` / `keyboard` / `both` |
| Settings | clipboard temps réel, notifications, sync thème, input distant, sécurité |
| Profile | initiales, device id, empreinte de clé, déconnexion |

## Architecture

```
core/
  Prefs.kt            préférences chiffrées (Android Keystore, AES256)
  Protocol.kt         schéma des trames (lire ../PROTOCOL.md)
  DeviceIdentity.kt   clé EC P-256 non exportable + empreinte
  Link.kt             client WebSocket TLS, pairing, journal, envois
  OcTheme.kt          palette reçue du PC (fallback: défaut Omarchy)
  Models.kt           Command / LogLine / CommandResult / LinkStatus
service/
  LinkService.kt      service de premier plan : lien + presse-papiers temps réel
  NotificationForwarder.kt  NotificationListenerService -> PC
ui/
  theme/Theme.kt      thème Compose (JetBrains Mono, dark, formes 3-4dp)
  components/Oc.kt    Panel, OcButton, OcTextField, OcToggle, Chip, LogView, Avatar
  screens/*           les 7 écrans
  App.kt              navigation + barre du bas (Home/Commands/Remote/Settings/Profile)
```

## Build

```bash
cd android-app
./gradlew assembleDebug      # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease
```

JDK 17+ requis (la CI utilise Temurin 17).

## Après l'installation

1. **Get started** -> domaine + code affiché par `omarchy-connect` sur le PC
   (`Continue without an account` = mode démo, UI entièrement navigable).
2. Settings -> *grant access* pour l'accès aux notifications.
3. Le service de premier plan maintient le lien et synchronise le presse-papiers.

## Sécurité

- `usesCleartextTraffic=false` : uniquement `wss://`
- clé privée dans le Keystore Android (jamais exportée)
- jeton de session dans `EncryptedSharedPreferences`
- `allowBackup=false` : rien ne sort via ADB backup
- emreinte du PC vérifiée à chaque connexion (détection MITM)
- aucun SDK tiers, aucune télémétrie
