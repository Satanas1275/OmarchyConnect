package com.omarchyconnect.app.core

import java.security.MessageDigest

/**
 * Protocole OmarchyConnect (JSON newline-free, frames WebSocket texte).
 *
 * Le même schéma est implémenté côté Rust (`pc-app/PROTOCOL.md`).
 *
 * Client -> PC
 *   {"v":1,"t":"pair.request","device":..,"deviceId":..,"pubKey":..,"code":..}
 *   {"v":1,"t":"auth","token":..}
 *   {"v":1,"t":"theme.get"}
 *   {"v":1,"t":"commands.list"}
 *   {"v":1,"t":"command.run","id":..}
 *   {"v":1,"t":"clipboard","text":..}
 *   {"v":1,"t":"notification","app":..,"title":..,"text":..,"at":..}
 *   {"v":1,"t":"input","kind":"move|scroll|click|key|text","dx":..,"dy":..,"button":..,"key":..,"text":..,"mods":[..]}
 *   {"v":1,"t":"file.offer","name":..,"size":..}      (le contenu suit en HTTP POST /files)
 *   {"v":1,"t":"ping"}
 *
 * PC -> Client
 *   {"v":1,"t":"hello","pc":..,"user":..,"since":..,"fp":..}
 *   {"v":1,"t":"pair.ok","token":..,"user":..,"since":..}
 *   {"v":1,"t":"auth.ok","user":..}
 *   {"v":1,"t":"theme","c":{...}}
 *   {"v":1,"t":"commands","items":[{id,name,description}]}
 *   {"v":1,"t":"command.result","id":..,"ok":true,"out":..}
 *   {"v":1,"t":"clipboard","text":..}
 *   {"v":1,"t":"file.offer","id":..,"name":..,"url":..}
 *   {"v":1,"t":"error","code":..,"message":..}
 *   {"v":1,"t":"pong"}
 */
object Protocol {

    const val VERSION = 1

    const val T_PAIR_REQUEST = "pair.request"
    const val T_PAIR_OK = "pair.ok"
    const val T_AUTH = "auth"
    const val T_AUTH_OK = "auth.ok"
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
    const val T_ERROR = "error"
    const val T_PING = "ping"
    const val T_PONG = "pong"

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(data)
            .joinToString("") { "%02x".format(it) }

    fun normalizeDomain(raw: String): String {
        var d = raw.trim().lowercase()
        d = d.removePrefix("https://").removePrefix("http://")
        d = d.removePrefix("wss://").removePrefix("ws://")
        d = d.substringBefore("/")
        return d
    }
}
