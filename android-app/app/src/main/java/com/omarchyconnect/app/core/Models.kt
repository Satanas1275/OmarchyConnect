package com.omarchyconnect.app.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Une commande définie sur le PC (omarchy-connect.toml) et jouable depuis le téléphone. */
data class Command(
    val id: String,
    val name: String,
    val description: String,
    val danger: Boolean = false
)

/** Ligne du journal d'activité, rendue en police monospace façon terminal. */
data class LogLine(
    val at: Long,
    val level: String, // info | ok | warn | err | net
    val text: String
) {
    val time: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))
}

/** Résultat d'une commande exécutée sur le PC. */
data class CommandResult(
    val id: String,
    val name: String,
    val ok: Boolean,
    val out: String,
    val at: Long = System.currentTimeMillis()
)

/** Annonces de fichiers venant du PC. */
data class FileOffer(
    val id: String,
    val name: String,
    val url: String,
    val size: Long = 0
)

enum class LinkStatus {
    OFFLINE,      // pas de connexion / démo
    PAIRING,      // échange du code d'appairage
    CONNECTING,   // WebSocket en cours
    ONLINE,       // authentifié
    ERROR
}

object Demo {
    val commands = listOf(
        Command("lock", "Lock session", "Verrouille l'écran (loginctl lock-session)"),
        Command("screenshot", "Screenshot", "Capture l'écran entier vers ~/Pictures"),
        Command("volume_up", "Volume +5%", "Augmente le volume PulseAudio/PipeWire"),
        Command("mute", "Toggle mute", "Coupe ou rétablit le micro"),
        Command("media", "Play / Pause", "Contrôle de la lecture en cours"),
        Command("browser", "Open browser", "Ouvre Firefox sur la dernière session"),
        Command("shutdown", "Shutdown", "Éteint le PC", danger = true)
    )

    fun boot(): List<LogLine> {
        val now = System.currentTimeMillis()
        return listOf(
            LogLine(now - 42_000, "net", "link wss://omarchy.local established"),
            LogLine(now - 41_000, "ok", "handshake ok · tls1.3 · device verified"),
            LogLine(now - 40_000, "info", "theme synced from wm: catppuccin-mocha"),
            LogLine(now - 38_000, "info", "7 commands loaded from PC"),
            LogLine(now - 12_000, "net", "clipboard sync: realtime"),
            LogLine(now - 5_000, "ok", "ready.")
        )
    }
}
