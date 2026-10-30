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
