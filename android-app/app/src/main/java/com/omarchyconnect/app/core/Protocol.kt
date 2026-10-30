package com.omarchyconnect.app.core

import java.security.MessageDigest

/**
 * Protocole OmarchyConnect (v1) — frames applicatives **chiffrées de bout en
 * bout**, transportées telles quelles par le relais :
 *
 *   relais : {"t":"relay","from":"…","payload":"<base64 AES-GCM>"}
 *   BT     : {"t":"relay","payload":"<base64 AES-GCM>"} (une ligne JSON/frame)
 *
 * Le payload déchiffré est un objet `{"v":1,"t":"…", ...}`.
 *
 * Client -> PC
 *   theme.get / commands.list / command.run / clipboard / notification
 *   input / file.begin / file.chunk / file.end / ping
 *
 * PC -> Client
 *   theme / commands / command.result / clipboard / file.result / error / pong
 */
object Protocol {

    const val VERSION = 1

    // --- Contrôle du relais (non chiffré, lisible par le serveur) ---
    const val T_RELAY = "relay"
    const val T_AUTH_CHALLENGE = "auth.challenge"
    const val T_AUTH = "auth"
    const val T_AUTH_OK = "auth.ok"
    const val T_PAIR_JOIN = "pair.join"
    const val T_PAIR_DONE = "pair.done"
    const val T_PEER = "peer"
    const val T_UNPAIRED = "unpaired"

    // --- Bluetooth (handshake en clair sur le lien RFCOMM chiffré par SSP) ---
    const val T_BT_HELLO = "bt.hello"
    const val T_BT_ACCEPT = "bt.accept"

    // --- Trames applicatives ---
    const val T_PAIR_REQUEST = "pair.request"
    const val T_PAIR_OK = "pair.ok"
    const val T_HELLO = "hello"
    const val T_THEME_GET = "theme.get"
    const val T_THEME = "theme"
    const val T_COMMANDS_LIST = "commands.list"
    const val T_COMMANDS = "commands"
    const val T_COMMAND_RUN = "command.run"
    const val T_COMMAND_RESULT = "command.result"
    const val T_CLIPBOARD = "clipboard"
    const val T_NOTIFICATION = "notification"
    const val T_INPUT = "input"
    const val T_FILE_OFFER = "file.offer"
    const val T_FILE_BEGIN = "file.begin"
    const val T_FILE_CHUNK = "file.chunk"
    const val T_FILE_END = "file.end"
    const val T_FILE_RESULT = "file.result"
    const val T_ERROR = "error"
    const val T_PING = "ping"
    const val T_PONG = "pong"

    /** UUID SPP classique (RFCOMM) utilisé par Android et BlueZ. */
    const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(data)
            .joinToString("") { "%02x".format(it) }

    /** Normalise une URL de relais : sans slash final, schéma https par défaut. */
    fun normalizeBase(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) return Prefs.DEFAULT_RELAY
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://$value"
        }
        return value.trimEnd('/')
    }

    /** Hôte seul (affichage), ex. `omarchy-connect.rocknite-studio.com`. */
    fun hostOf(base: String): String =
        base.trim()
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("wss://").removePrefix("ws://")
            .substringBefore("/")

    /** Ancien nom conservé pour le code existant. */
    fun normalizeDomain(raw: String): String = hostOf(normalizeBase(raw))
}
