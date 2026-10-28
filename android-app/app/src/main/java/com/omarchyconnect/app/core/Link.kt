package com.omarchyconnect.app.core

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Le "lien" vers le PC : WebSocket TLS (ou mode démo hors-ligne).
 *
 * Singleton volontaire : le même objet est partagé par l'UI, le service de
 * premier plan et le service de notifications. Toutes les écritures passent
 * par [log] / [send] pour garder un journal unique et auditable.
 */
object Link {

    private const val TAG = "OcLink"
    private const val PROTO = 1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private var ws: WebSocket? = null
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private var keepAliveJob: kotlinx.coroutines.Job? = null
    private var appContext: Context? = null
    private var lastMoveAt = 0L
    private var lastPingAt = 0L
    private var pairDeadlineJob: kotlinx.coroutines.Job? = null

    // --- État observable par l'UI ---

    val status = MutableStateFlow(LinkStatus.OFFLINE)
    val demo = MutableStateFlow(false)
    val domain = MutableStateFlow("")
    val pcName = MutableStateFlow("")
    val transport = MutableStateFlow("—")
    val latency = MutableStateFlow(0L)
    val user = MutableStateFlow("user")
    val userSince = MutableStateFlow("2026")
    val commands = MutableStateFlow(Demo.commands)
    val log = MutableStateFlow(Demo.boot())
    val theme = MutableStateFlow(OcTheme.Default)
    val lastResult = MutableStateFlow<CommandResult?>(null)
    val lastClipboard = MutableStateFlow("")
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        domain.value = Prefs.domain
        user.value = Prefs.userName
        userSince.value = Prefs.userSince
        if (Prefs.paired && Prefs.domain.isNotBlank() && !demo.value) {
            connect()
        }
    }

    // ---------------------------------------------------------------- journal

    fun log(level: String, text: String) {
        val line = LogLine(System.currentTimeMillis(), level, text)
        log.value = (log.value + line).takeLast(200)
    }

    // ------------------------------------------------------------- connexion

    fun wsUrl(): String = "wss://${domain.value}/ws"

    fun connect() {
        if (domain.value.isBlank()) return
        if (ws != null) return
        demo.value = false
        status.value = LinkStatus.CONNECTING
        transport.value = "tls"
        log("net", "dial wss://${domain.value}/ws")

        val req = Request.Builder()
            .url(wsUrl())
            .addHeader("X-OC-Device", Prefs.deviceId.ifBlank { "unknown" })
            .addHeader("X-OC-Version", "1")
            .build()

        ws = http.newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    log("net", "socket open · ${response.protocol} · ${domain.value}")
                    if (Prefs.token.isNotBlank()) {
                        send(Protocol.T_AUTH, JSONObject().put("token", Prefs.token))
                    }
                    startKeepAlive()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    log("warn", "binary frame ignored (${bytes.size} bytes)")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    log("warn", "socket closed ($code) ${reason.ifBlank { "" }}")
                    ws = null
                    if (!demo.value) {
                        status.value = LinkStatus.OFFLINE
                        scheduleReconnect()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    log("err", "socket failure: ${t.javaClass.simpleName}: ${t.message}")
                    ws = null
                    if (!demo.value) {
                        status.value = LinkStatus.ERROR
                        error.value = t.message ?: "connection failed"
                        scheduleReconnect()
                    }
                }
            }
        )
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var wait = 2_000L
            while (!demo.value && Prefs.paired && status.value != LinkStatus.ONLINE) {
                delay(wait)
                wait = (wait * 2).coerceAtMost(30_000L)
                if (ws == null && domain.value.isNotBlank()) {
                    log("net", "reconnecting…")
                    connect()
                    return@launch
                }
            }
        }
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (ws != null) {
                delay(20_000)
                lastPingAt = System.currentTimeMillis()
                send(Protocol.T_PING, JSONObject())
            }
        }
    }

    fun disconnect(reason: String = "user") {
        reconnectJob?.cancel()
        keepAliveJob?.cancel()
        ws?.close(1000, reason)
        ws = null
        status.value = LinkStatus.OFFLINE
        log("warn", "disconnected ($reason)")
    }

    // -------------------------------------------------------------- appairage

    /** Appairage : envoie le code + la clé publique du téléphone (Keystore). */
    fun pair(code: String) {
        if (domain.value.isBlank()) {
            status.value = LinkStatus.ERROR
            error.value = "Domaine requis"
            return
        }
        Prefs.domain = domain.value
        if (Prefs.deviceId.isBlank()) Prefs.deviceId = UUID.randomUUID().toString()
        if (Prefs.deviceName.isBlank()) Prefs.deviceName = "Android phone"

        status.value = LinkStatus.PAIRING
        error.value = null
        demo.value = false
        log("net", "pairing with code ${code.replace(Regex("."), "*")}")

        if (ws == null) connect()
        status.value = LinkStatus.PAIRING
        pairDeadlineJob?.cancel()
        pairDeadlineJob = scope.launch {
            delay(30_000)
            if (status.value == LinkStatus.PAIRING) {
                status.value = LinkStatus.ERROR
                error.value = "Appairage expiré (30s)"
                log("err", "pairing timeout")
            }
        }

        scope.launch {
            // laisse la socket s'ouvrir
            var waited = 0
            while (ws == null && waited < 10_000) {
                delay(250); waited += 250
            }
            delay(300)
            send(
                Protocol.T_PAIR_REQUEST,
                JSONObject()
                    .put("v", PROTO)
                    .put("device", Prefs.deviceName)
                    .put("deviceId", Prefs.deviceId)
                    .put("name", Build.MODEL ?: "Android")
                    .put("pubKey", DeviceIdentity.publicKeyBase64())
                    .put("code", code.trim())
            )
        }
    }

    fun acceptPairing(response: JSONObject) {
        val token = response.optString("token", "")
        if (token.isBlank()) {
            status.value = LinkStatus.ERROR
            error.value = "Appairage refusé"
            return
        }
        Prefs.token = token
        Prefs.paired = true
        Prefs.domain = domain.value
        if (response.has("user")) {
            Prefs.userName = response.optString("user", Prefs.userName)
            user.value = Prefs.userName
        }
        if (response.has("since")) {
            Prefs.userSince = response.optString("since", Prefs.userSince)
            userSince.value = Prefs.userSince
        }
        pairDeadlineJob?.cancel()
        status.value = LinkStatus.ONLINE
        error.value = null
        log("ok", "paired · session token stored in Keystore")
        afterAuthenticated()
    }

    fun forget() {
        disconnect("unpair")
        Prefs.clear()
        demo.value = false
        user.value = "user"
        userSince.value = "2026"
        domain.value = ""
        commands.value = emptyList()
        log("warn", "device unpaired, local keys cleared")
    }

    // ----------------------------------------------------------------- démo

    fun enableDemo() {
        demo.value = true
        status.value = LinkStatus.OFFLINE
        domain.value = "demo.local"
        pcName.value = "arch-pc"
        transport.value = "demo"
        commands.value = Demo.commands
        theme.value = OcTheme.Default
        user.value = "satanas"
        userSince.value = "2026"
        log("net", "demo mode · no PC attached, UI fully browsable")
        log("info", "7 commands loaded (demo)")
    }

    // ------------------------------------------------------------ messages

    private fun handleMessage(raw: String) {
        try {
            val msg = JSONObject(raw)
            when (msg.optString("t")) {
                Protocol.T_HELLO -> {
                    pcName.value = msg.optString("pc", pcName.value)
                    val fp = msg.optString("fp", "")
                    if (Prefs.paired && Prefs.pcFingerprint.isNotBlank() && fp.isNotBlank() &&
                        !fp.equals(Prefs.pcFingerprint, true)
                    ) {
                        log("err", "PC fingerprint mismatch, refusing link")
                        status.value = LinkStatus.ERROR
                        error.value = "Empreinte du PC incorrecte (MITM possible ?)"
                        ws?.close(1008, "fingerprint mismatch")
                        return
                    }
                    if (fp.isNotBlank()) Prefs.pcFingerprint = fp
                    user.value = msg.optString("user", user.value)
                    userSince.value = msg.optString("since", userSince.value)
                    log("ok", "hello from ${pcName.value}")
                    if (Prefs.token.isNotBlank()) {
                        send(Protocol.T_AUTH, JSONObject().put("token", Prefs.token))
                    }
                }

                Protocol.T_AUTH_OK -> {
                    status.value = LinkStatus.ONLINE
                    error.value = null
                    user.value = msg.optString("user", user.value)
                    log("ok", "authenticated · ${domain.value}")
                    afterAuthenticated()
                }

                Protocol.T_PAIR_OK -> acceptPairing(msg)

                Protocol.T_THEME -> {
                    theme.value = OcTheme.fromJson(msg.toString())
                    log("info", "theme synced: ${theme.value.name}")
                }

                Protocol.T_COMMANDS -> {
                    val items = msg.optJSONArray("items") ?: JSONArray()
                    val list = buildList {
                        for (i in 0 until items.length()) {
                            val c = items.getJSONObject(i)
                            add(
                                Command(
                                    id = c.optString("id", "cmd$i"),
                                    name = c.optString("name", "cmd$i"),
                                    description = c.optString("description", ""),
                                    danger = c.optBoolean("danger", false)
                                )
                            )
                        }
                    }
                    commands.value = list.ifEmpty { Demo.commands }
                    log("info", "${commands.value.size} commands loaded from PC")
                }

                Protocol.T_COMMAND_RESULT -> {
                    val res = CommandResult(
                        id = msg.optString("id"),
                        name = msg.optString("name", msg.optString("id")),
                        ok = msg.optBoolean("ok", false),
                        out = msg.optString("out", "")
                    )
                    lastResult.value = res
                    log(if (res.ok) "ok" else "err", "$ ${res.name} -> ${res.out.lineSequence().firstOrNull().orEmpty()}")
                }

                Protocol.T_CLIPBOARD -> {
                    val text = msg.optString("text", "")
                    lastClipboard.value = text
                    log("net", "clipboard <- pc (${text.length} chars)")
                }

                Protocol.T_FILE_OFFER -> {
                    log("info", "file offer: ${msg.optString("name")}")
                    receiveFile(msg)
                }

                Protocol.T_ERROR -> {
                    val m = msg.optString("message", "error")
                    error.value = m
                    log("err", "${msg.optString("code", "?")}: $m")
                    if (status.value == LinkStatus.PAIRING) status.value = LinkStatus.ERROR
                }

                Protocol.T_PONG -> {
                    if (lastPingAt > 0) latency.value = System.currentTimeMillis() - lastPingAt
                }

                else -> log("warn", "unknown frame: ${msg.optString("t")}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "bad frame", t)
            log("err", "bad frame: ${t.message}")
        }
    }

    private fun afterAuthenticated() {
        send(Protocol.T_COMMANDS_LIST, JSONObject())
        if (Prefs.themeSync) send(Protocol.T_THEME_GET, JSONObject())
    }

    // ------------------------------------------------------------- émissions

    fun send(type: String, payload: JSONObject): Boolean {
        val frame = payload
            .put("v", PROTO)
            .put("t", type)
        val ok = ws?.send(frame.toString()) == true
        if (!ok && type != Protocol.T_PING) log("warn", "frame not sent (socket closed): $type")
        return ok
    }

    fun runCommand(cmd: Command) {
        if (demo.value) {
            lastResult.value = CommandResult(
                id = cmd.id,
                name = cmd.name,
                ok = true,
                out = if (cmd.danger) "demo: '${cmd.name}' would run on the PC" else "ok"
            )
            log("ok", "$ ${cmd.name} -> ok (demo)")
            return
        }
        log("info", "$ ${cmd.name} …")
        send(Protocol.T_COMMAND_RUN, JSONObject().put("id", cmd.id))
    }

    fun sendClipboard(text: String) {
        if (!Prefs.clipboardSync) return
        if (text.isBlank()) return
        if (text == lastClipboard.value) return
        lastClipboard.value = text
        if (demo.value) {
            log("net", "clipboard -> pc (${text.length} chars, demo)")
            return
        }
        if (status.value != LinkStatus.ONLINE) return
        if (send(Protocol.T_CLIPBOARD, JSONObject().put("text", text))) {
            log("net", "clipboard -> pc (${text.length} chars)")
        }
    }

    fun pushNotification(pkg: String, appName: String, title: String, text: String) {
        if (!Prefs.forwardNotifications) return
        if (status.value != LinkStatus.ONLINE) return
        send(
            Protocol.T_NOTIFICATION,
            JSONObject()
                .put("pkg", pkg)
                .put("app", appName)
                .put("title", title)
                .put("text", text)
                .put("at", System.currentTimeMillis())
        )
    }

    /** Entrée distante : souris / clavier (l'un ou l'autre ou les deux). */
    fun input(
        kind: String,
        dx: Float = 0f,
        dy: Float = 0f,
        button: String = "",
        key: String = "",
        text: String = "",
        mods: List<String> = emptyList()
    ) {
        if (!Prefs.remoteInput) return
        if (kind == "move") {
            val now = System.currentTimeMillis()
            if (now - lastMoveAt < 25) return
            lastMoveAt = now
        }
        if (demo.value) return
        val o = JSONObject().put("kind", kind)
        if (dx != 0f) o.put("dx", dx)
        if (dy != 0f) o.put("dy", dy)
        if (button.isNotBlank()) o.put("button", button)
        if (key.isNotBlank()) o.put("key", key)
        if (text.isNotBlank()) o.put("text", text)
        if (mods.isNotEmpty()) o.put("mods", JSONArray(mods))
        send(Protocol.T_INPUT, o)
    }

    // --------------------------------------------------------------- fichiers

    private fun displayName(resolver: ContentResolver, uri: Uri): String {
        resolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) return c.getString(i) ?: "file"
        }
        return uri.lastPathSegment ?: "file"
    }

    /** Envoi d'un ou plusieurs fichiers vers le PC (POST /files, TLS + jeton). */
    fun sendFiles(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (demo.value) {
            uris.forEach { log("info", "file -> pc: ${displayName(context.contentResolver, it)} (demo)") }
            return
        }
        if (Prefs.token.isBlank()) {
            log("err", "not paired, file refused")
            return
        }
        busy.value = true
        scope.launch {
            var sent = 0
            uris.forEach { uri ->
                val name = displayName(context.contentResolver, uri)
                try {
                    val body = okhttp3.MultipartBody.Builder()
                        .setType(okhttp3.MultipartBody.FORM)
                        .addFormDataPart("name", name)
                        .addFormDataPart(
                            "file",
                            name,
                            object : okhttp3.RequestBody() {
                                override fun contentType(): okhttp3.MediaType =
                                    "application/octet-stream".toMediaType()

                                override fun writeTo(sink: okio.BufferedSink) {
                                    val input = context.contentResolver.openInputStream(uri)
                                        ?: throw IOException("cannot open $uri")
                                    input.use { stream ->
                                        val buf = ByteArray(64 * 1024)
                                        while (true) {
                                            val n = stream.read(buf)
                                            if (n <= 0) break
                                            sink.write(buf, 0, n)
                                        }
                                    }
                                }
                            }
                        )
                        .build()

                    val req = Request.Builder()
                        .url("https://${domain.value}/api/files")
                        .addHeader("Authorization", "Bearer ${Prefs.token}")
                        .post(body)
                        .build()

                    http.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            sent++
                            log("ok", "file -> pc: $name")
                            send(
                                Protocol.T_FILE_OFFER,
                                JSONObject().put("name", name).put("size", 0)
                            )
                        } else {
                            log("err", "file $name refused: HTTP ${resp.code}")
                        }
                    }
                } catch (t: Throwable) {
                    log("err", "file $name failed: ${t.message}")
                }
            }
            if (sent == uris.size) log("ok", "$sent file(s) transferred")
            busy.value = false
        }
    }

    /** Réception d'un fichier venant du PC -> Téléchargements. */
    private fun receiveFile(msg: JSONObject) {
        val offer = FileOffer(
            id = msg.optString("id", UUID.randomUUID().toString()),
            name = msg.optString("name", "file"),
            url = msg.optString("url", ""),
            size = msg.optLong("size", 0)
        )
        if (offer.url.isBlank()) return
        val context = appContext ?: return
        scope.launch {
            try {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                val req = android.app.DownloadManager.Request(Uri.parse(offer.url))
                    .setTitle(offer.name)
                    .setDescription("OmarchyConnect")
                    .addRequestHeader("Authorization", "Bearer ${Prefs.token}")
                    .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalPublicDir(
                        android.os.Environment.DIRECTORY_DOWNLOADS,
                        offer.name
                    )
                dm.enqueue(req)
                log("ok", "file <- pc: ${offer.name} -> Download/")
            } catch (t: Throwable) {
                log("err", "receive ${offer.name} failed: ${t.message}")
            }
        }
    }
}
