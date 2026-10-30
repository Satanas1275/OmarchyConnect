package com.omarchyconnect.app.core

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Le lien vers le PC.
 *
 * Deux transports, un seul protocole applicatif :
 *
 *  1. **relais** (Internet) — le téléphone et le PC sont tous deux clients du
 *     serveur `omarchy-connect.rocknite-studio.com`. Auth par signature ECDSA
 *     d'un nonce (clé privée Keystore jamais transmise), puis frames
 *     `{"t":"relay","payload":…}` **opaques** pour le serveur : le payload est
 *     chiffré AES-256-GCM avec la clé dérivée par ECDH + HKDF.
 *  2. **Bluetooth RFCOMM/SPP** — repli automatique (ou mode « sans compte »)
 *     quand le relais est injoignable. Le lien RFCOMM est déjà chiffré par
 *     l'appairage Bluetooth du système ; on ajoute une dérivation ECDH + les
 *     mêmes frames AES-GCM (défense en profondeur).
 *
 * Singleton volontaire : partagé par l'UI, le service de premier plan et le
 * service de notifications.
 */
object Link {

    private const val TAG = "OcLink"
    private const val PROTO = 1
    private const val CHUNK = 24 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // --- état transport ---
    private var relayWs: WebSocket? = null
    private var relayAuthed = false
    private var btSocket: BluetoothSocket? = null
    private var btWriter: BufferedWriter? = null
    private val btLock = Any()
    private var sessionKey: ByteArray? = null
    private var mode = "" // relay | bt
    private var connecting = false
    private var userClosed = false
    private var retryAttempt = 0
    private var retryJob: Job? = null
    private var keepAliveJob: Job? = null
    private var appContext: Context? = null
    private var lastMoveAt = 0L
    private var lastPingAt = 0L
    private var btFallbackTried = false

    // --- État observable par l'UI ---

    val status = MutableStateFlow(LinkStatus.OFFLINE)
    val domain = MutableStateFlow("")
    val relayBase = MutableStateFlow(Prefs.DEFAULT_RELAY)
    val pcName = MutableStateFlow("")
    val transport = MutableStateFlow("—")
    val latency = MutableStateFlow(0L)
    val user = MutableStateFlow("user")
    val userSince = MutableStateFlow("2026")
    val commands = MutableStateFlow<List<Command>>(emptyList())
    val log = MutableStateFlow<List<LogLine>>(emptyList())
    val theme = MutableStateFlow(OcTheme.Default)
    val lastResult = MutableStateFlow<CommandResult?>(null)
    val lastClipboard = MutableStateFlow("")
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val btAvailable = MutableStateFlow(false)
    val btDevices = MutableStateFlow<List<String>>(emptyList())

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        relayBase.value = Prefs.relayBase
        domain.value = if (Prefs.transport == "bt" && Prefs.btName.isNotBlank()) {
            Prefs.btName
        } else {
            Protocol.hostOf(Prefs.relayBase)
        }
        pcName.value = Prefs.pcNamePref
        user.value = Prefs.userName
        userSince.value = Prefs.userSince
        sessionKey = Prefs.sessionKey.takeIf { it.length == 64 }?.let {
            runCatching { Crypto.unhex(it) }.getOrNull()
        }
        refreshBtDevices()
        if (Prefs.paired && !userClosed) connect()
    }

    // ---------------------------------------------------------------- journal

    fun log(level: String, text: String) {
        val line = LogLine(System.currentTimeMillis(), level, text)
        log.value = (log.value + line).takeLast(200)
    }

    // ------------------------------------------------------------- transports

    fun setEndpoint(raw: String): String {
        val base = Protocol.normalizeBase(raw)
        Prefs.relayBase = base
        relayBase.value = base
        if (Prefs.transport != "bt") domain.value = Protocol.hostOf(base)
        return base
    }

    fun connect() {
        if (status.value == LinkStatus.ONLINE || connecting) return
        userClosed = false
        retryJob?.cancel()
        if (Prefs.transport == "bt" && btPossible()) {
            connectBluetooth()
        } else {
            connectRelay(fallback = true)
        }
    }

    /**
     * « Continue without an account » : transport Bluetooth uniquement.
     * Aucun compte, aucun serveur : le PC doit être appairé dans les réglages
     * Bluetooth d'Android, puis le daemon Rust expose le profil SPP.
     */
    fun connectBluetooth() {
        if (connecting || status.value == LinkStatus.ONLINE) return
        userClosed = false
        retryJob?.cancel()
        val ctx = appContext ?: return
        if (!hasBtPermission(ctx)) {
            status.value = LinkStatus.ERROR
            error.value = "Bluetooth permission required (Android 12+)"
            log("err", "BLUETOOTH_CONNECT non accordée")
            return
        }
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            status.value = LinkStatus.ERROR
            error.value = "Bluetooth is off — enable it in Android settings"
            log("err", "Bluetooth désactivé")
            return
        }
        val target = pickBtDevice(adapter)
        if (target == null) {
            status.value = LinkStatus.ERROR
            error.value = "No bonded PC — pair the computer in Bluetooth settings first"
            log("err", "aucun appareil Bluetooth appairé")
            refreshBtDevices()
            return
        }

        connecting = true
        mode = "bt"
        status.value = LinkStatus.CONNECTING
        transport.value = "bluetooth · rfcomm"
        error.value = null
        log("net", "bt dial ${target.name} (${target.address})")

        scope.launch {
            var socket: BluetoothSocket? = null
            try {
                socket = target.createRfcommSocketToServiceRecord(
                    UUID.fromString(Protocol.SPP_UUID)
                )
                try {
                    adapter.cancelDiscovery()
                } catch (_: Throwable) {
                }
                socket.connect()
                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))

                val hello = JSONObject()
                    .put("t", Protocol.T_BT_HELLO)
                    .put("device_id", ensureDeviceId())
                    .put("name", deviceName())
                    .put("auth_pubkey", DeviceIdentity.publicKeyBase64())
                    .put("dh_pubkey", DeviceIdentity.dhPublicKeyBase64())
                writeBtLine(writer, hello.toString())

                val line = reader.readLine()
                    ?: throw IOException("BT closed during handshake")
                val accept = JSONObject(line)
                if (accept.optString("t") != Protocol.T_BT_ACCEPT) {
                    throw IOException("BT handshake refusé: ${accept.optString("t")}")
                }
                val peerDh = accept.optString("pc_dh_pubkey", accept.optString("pc_pubkey"))
                if (peerDh.isBlank()) throw IOException("PC sans clé DH")
                val key = DeviceIdentity.sessionKey(peerDh)

                sessionKey = key
                Prefs.sessionKey = Crypto.hex(key)
                Prefs.peerId = accept.optString("pc_id", "bt")
                Prefs.pcNamePref = accept.optString("pc", target.name)
                Prefs.btAddress = target.address
                Prefs.btName = target.name
                Prefs.transport = "bt"
                Prefs.paired = true
                pcName.value = Prefs.pcNamePref
                domain.value = target.name

                btSocket = socket
                btWriter = writer
                connecting = false
                retryAttempt = 0
                btFallbackTried = true
                relayAuthed = false
                status.value = LinkStatus.ONLINE
                transport.value = "bluetooth · rfcomm"
                log("ok", "bluetooth link · ${target.name} · ECDH key derived")
                afterAuthenticated()

                btReadLoop(reader)
            } catch (t: Throwable) {
                Log.w(TAG, "bt", t)
                log("err", "bt: ${t.message ?: t.javaClass.simpleName}")
                runCatching { socket?.close() }
                btClosed(t.message ?: "bluetooth link lost")
            } finally {
                connecting = false
            }
        }
    }

    private fun btReadLoop(reader: BufferedReader) {
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                handleTopFrame(line)
            }
        } catch (t: Throwable) {
            if (btSocket != null) log("warn", "bt read: ${t.message}")
        } finally {
            if (btSocket != null) btClosed("bluetooth link closed")
        }
    }

    private fun btClosed(reason: String) {
        btSocket = null
        btWriter = null
        if (mode == "bt" && !userClosed && status.value != LinkStatus.OFFLINE) {
            status.value = LinkStatus.OFFLINE
            log("warn", "bt closed: $reason")
            if (Prefs.paired) scheduleRetry()
        }
    }

    private fun writeBtLine(writer: BufferedWriter, text: String): Boolean = synchronized(btLock) {
        return try {
            writer.write(text)
            writer.write("\n")
            writer.flush()
            true
        } catch (t: Throwable) {
            log("err", "bt write: ${t.message}")
            false
        }
    }

    private fun pickBtDevice(adapter: BluetoothAdapter): android.bluetooth.BluetoothDevice? {
        val bonded = try {
            adapter.bondedDevices ?: emptySet()
        } catch (t: Throwable) {
            log("err", "bt bonded: ${t.message}")
            emptySet()
        }
        refreshBtDevices(bonded)
        if (bonded.isEmpty()) return null
        Prefs.btAddress.takeIf { it.isNotBlank() }?.let { addr ->
            bonded.firstOrNull { it.address.equals(addr, true) }?.let { return it }
        }
        return bonded.firstOrNull { device ->
            val n = (device.name ?: "").lowercase()
            n.contains("omarchy") || n.contains("arch") || n.contains("pc") || n.contains("desktop")
        } ?: bonded.first()
    }

    private fun refreshBtDevices(bonded: Set<android.bluetooth.BluetoothDevice>? = null) {
        val ctx = appContext ?: return
        if (!hasBtPermission(ctx)) {
            btAvailable.value = false
            btDevices.value = emptyList()
            return
        }
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val devices = bonded ?: try {
            adapter.bondedDevices ?: emptySet()
        } catch (_: Throwable) {
            emptySet()
        }
        btDevices.value = devices.map { "${it.name ?: "unknown"} · ${it.address}" }
        btAvailable.value = adapter.isEnabled && devices.isNotEmpty()
    }

    private fun btPossible(): Boolean {
        val ctx = appContext ?: return false
        if (!hasBtPermission(ctx)) return false
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        if (!adapter.isEnabled) return false
        return try {
            !adapter.bondedDevices.isNullOrEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    private fun hasBtPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------- relais HTTP

    private fun httpJson(method: String, url: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap()): JSONObject {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        when (method) {
            "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody(jsonType))
            else -> builder.get()
        }
        http.newCall(builder.build()).execute().use { resp: Response ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("$url → HTTP ${resp.code} : ${text.take(200)}")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun challenge(base: String): String {
        val v = httpJson(
            "POST",
            "$base/api/challenge",
            JSONObject().put("device_id", ensureDeviceId())
        )
        val nonce = v.optString("nonce")
        if (nonce.isBlank()) throw IOException("challenge sans nonce")
        return nonce
    }

    private fun registerRelay(base: String) {
        httpJson(
            "POST",
            "$base/api/register",
            JSONObject()
                .put("device_id", ensureDeviceId())
                .put("role", "phone")
                .put("name", deviceName())
                .put("pubkey", DeviceIdentity.publicKeyBase64())
                .put("dh_pubkey", DeviceIdentity.dhPublicKeyBase64())
        )
        log("net", "device registered on relay (public keys only)")
    }

    private fun connectRelay(fallback: Boolean) {
        if (connecting || relayWs != null) return
        connecting = true
        mode = "relay"
        status.value = LinkStatus.CONNECTING
        val base = Protocol.normalizeBase(Prefs.relayBase)
        transport.value = "relay · ${if (base.startsWith("https")) "wss" else "ws"}"
        error.value = null
        Prefs.relayBase = base
        domain.value = Protocol.hostOf(base)
        log("net", "relay $base")

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    registerRelay(base)
                    openWs(base)
                }
            } catch (t: Throwable) {
                connecting = false
                Log.w(TAG, "relay", t)
                log("err", "relay: ${t.message ?: t.javaClass.simpleName}")
                error.value = "Relay unreachable: ${t.message ?: "error"}"
                status.value = LinkStatus.ERROR
                if (fallback && btPossible() && !btFallbackTried) {
                    btFallbackTried = true
                    log("warn", "relay down → automatic Bluetooth fallback")
                    connectBluetooth()
                } else {
                    scheduleRetry()
                }
            }
        }
    }

    private fun openWs(base: String) {
        val root = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        val url = "$root/ws?device=${ensureDeviceId()}"
        val request = Request.Builder().url(url).build()
        relayAuthed = false
        relayWs = http.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    connecting = false
                    log("net", "relay socket open · ${response.protocol}")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleTopFrame(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    log("warn", "binary frame ignored (${bytes.size} bytes)")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    relayWs = null
                    relayAuthed = false
                    connecting = false
                    log("warn", "relay closed ($code) $reason")
                    if (!userClosed) {
                        status.value = LinkStatus.OFFLINE
                        scheduleRetry()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    relayWs = null
                    relayAuthed = false
                    connecting = false
                    log("err", "relay failure: ${t.javaClass.simpleName}: ${t.message}")
                    if (!userClosed) {
                        error.value = t.message ?: "connection failed"
                        status.value = LinkStatus.ERROR
                        if (btPossible() && !btFallbackTried) {
                            btFallbackTried = true
                            log("warn", "relay down → Bluetooth fallback")
                            connectBluetooth()
                        } else {
                            scheduleRetry()
                        }
                    }
                }
            }
        )
    }

    private fun scheduleRetry() {
        if (retryJob?.isActive == true || userClosed) return
        val wait = (2_000L shl retryAttempt.coerceAtMost(4)).coerceAtMost(30_000L)
        retryAttempt++
        retryJob = scope.launch {
            log("net", "reconnect in ${wait / 1000}s")
            delay(wait)
            if (!userClosed && Prefs.paired && status.value != LinkStatus.ONLINE) connect()
        }
    }

    fun disconnect(reason: String = "user") {
        userClosed = true
        retryJob?.cancel()
        keepAliveJob?.cancel()
        relayWs?.close(1000, reason)
        relayWs = null
        relayAuthed = false
        runCatching { btSocket?.close() }
        btSocket = null
        btWriter = null
        connecting = false
        status.value = LinkStatus.OFFLINE
        transport.value = "—"
        log("warn", "disconnected ($reason)")
    }

    // -------------------------------------------------------------- appairage

    /**
     * Appairage par code à 8 caractères (relais) :
     * announce (PC) → join (téléphone) → ECDH → finalize → paire persistée.
     */
    fun pair(code: String, endpoint: String = Prefs.relayBase) {
        val base = setEndpoint(endpoint)
        error.value = null
        status.value = LinkStatus.PAIRING
        transport.value = "relay · pairing"
        relayWs?.close(1000, "pairing")
        relayWs = null
        relayAuthed = false
        userClosed = false
        log("net", "pairing via ${Protocol.hostOf(base)} (code ${code.trim().uppercase()})")

        scope.launch {
            try {
                val id = ensureDeviceId()
                val join = withContext(Dispatchers.IO) {
                    httpJson(
                        "POST",
                        "$base/api/pair/join",
                        JSONObject()
                            .put("code", code.trim().uppercase())
                            .put("phone_id", id)
                            .put("phone_pubkey", DeviceIdentity.publicKeyBase64())
                            .put("phone_dh_pubkey", DeviceIdentity.dhPublicKeyBase64())
                            .put("phone_name", deviceName())
                    )
                }
                val pcId = join.optString("pc_id")
                if (pcId.isBlank()) throw IOException("réponse join sans pc_id")
                val pcDh = join.optString("pc_dh_pubkey", join.optString("pc_pubkey"))
                if (pcDh.isBlank()) throw IOException("réponse join sans clé DH du PC")
                val name = join.optString("pc_name", "omarchy")
                log("net", "code accepted by $name — deriving ECDH key")

                val key = DeviceIdentity.sessionKey(pcDh)
                withContext(Dispatchers.IO) {
                    httpJson(
                        "POST",
                        "$base/api/pair/finalize",
                        JSONObject().put("phone_id", id)
                    )
                }

                sessionKey = key
                Prefs.sessionKey = Crypto.hex(key)
                Prefs.peerId = pcId
                Prefs.pcNamePref = name
                Prefs.transport = "relay"
                Prefs.paired = true
                pcName.value = name
                relayBase.value = base
                domain.value = Protocol.hostOf(base)
                status.value = LinkStatus.CONNECTING
                log("ok", "paired with $name · E2E key derived (server cannot read)")
                openWs(base)
            } catch (t: Throwable) {
                Log.w(TAG, "pair", t)
                status.value = LinkStatus.ERROR
                error.value = t.message ?: "pairing failed"
                log("err", "pairing: ${t.message}")
            }
        }
    }

    /** Filet de sécurité si l'app a manqué `pair.done` : relit l'état signé. */
    fun refreshStatus() {
        val base = Protocol.normalizeBase(Prefs.relayBase)
        scope.launch {
            try {
                val st = withContext(Dispatchers.IO) {
                    val nonce = challenge(base)
                    val sig = DeviceIdentity.sign(nonce)
                    httpJson(
                        "GET",
                        "$base/api/pair/status?device=${ensureDeviceId()}",
                        headers = mapOf("X-OC-Nonce" to nonce, "X-OC-Sig" to sig)
                    )
                }
                if (st.optBoolean("paired", false)) {
                    val peer = st.optString("peer")
                    val dh = st.optString("peer_dh_pubkey", st.optString("peer_pubkey"))
                    if (dh.isNotBlank() && sessionKey == null) {
                        val key = DeviceIdentity.sessionKey(dh)
                        sessionKey = key
                        Prefs.sessionKey = Crypto.hex(key)
                        Prefs.peerId = peer
                    }
                }
            } catch (t: Throwable) {
                log("warn", "status: ${t.message}")
            }
        }
    }

    fun forget() {
        val base = Protocol.normalizeBase(Prefs.relayBase)
        val id = Prefs.deviceId
        if (id.isNotBlank() && Prefs.paired) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val nonce = challenge(base)
                        httpJson(
                            "POST",
                            "$base/api/pair/unpair",
                            JSONObject()
                                .put("device_id", id)
                                .put("nonce", nonce)
                                .put("sig", DeviceIdentity.sign(nonce))
                        )
                    }
                } catch (t: Throwable) {
                    log("warn", "unpair relay: ${t.message}")
                }
            }
        }
        disconnect("unpair")
        Prefs.clear()
        user.value = "user"
        userSince.value = "2026"
        domain.value = ""
        relayBase.value = Prefs.DEFAULT_RELAY
        commands.value = emptyList()
        sessionKey = null
        transport.value = "—"
        log("warn", "device unpaired, local session key cleared")
    }

    // ------------------------------------------------------- frames / crypto

    private fun handleTopFrame(raw: String) {
        try {
            val msg = JSONObject(raw)
            when (msg.optString("t")) {
                Protocol.T_AUTH_CHALLENGE -> {
                    val nonce = msg.optString("nonce")
                    val auth = JSONObject()
                        .put("t", Protocol.T_AUTH)
                        .put("nonce", nonce)
                        .put("sig", DeviceIdentity.sign(nonce))
                    sendRaw(auth.toString())
                }

                Protocol.T_AUTH_OK -> {
                    relayAuthed = true
                    connecting = false
                    mode = "relay"
                    status.value = LinkStatus.ONLINE
                    transport.value = "relay · ${if (Prefs.relayBase.startsWith("https")) "wss" else "ws"}"
                    error.value = null
                    retryAttempt = 0
                    btFallbackTried = false
                    log("ok", "authenticated on relay · ${domain.value}")
                    afterAuthenticated()
                }

                Protocol.T_RELAY -> {
                    val payload = msg.optString("payload")
                    val key = sessionKey
                    if (key == null) {
                        log("warn", "relay frame before E2E key — ignored")
                        return
                    }
                    try {
                        val plain = Crypto.aesGcmDecrypt(key, Crypto.b64d(payload))
                        handleAppFrame(String(plain, Charsets.UTF_8))
                    } catch (t: Throwable) {
                        log("err", "E2E decrypt failed: ${t.message}")
                    }
                }

                Protocol.T_PEER -> log(
                    if (msg.optString("state") == "online") "ok" else "warn",
                    "peer ${msg.optString("device")} ${msg.optString("state")}"
                )

                Protocol.T_UNPAIRED -> {
                    sessionKey = null
                    Prefs.sessionKey = ""
                    Prefs.paired = false
                    status.value = LinkStatus.OFFLINE
                    log("warn", "unpaired by the other device")
                }

                Protocol.T_PONG -> {
                    if (lastPingAt > 0) latency.value = System.currentTimeMillis() - lastPingAt
                }

                Protocol.T_ERROR -> {
                    val m = msg.optString("error", msg.optString("message", "error"))
                    error.value = m
                    log("err", "relay: $m")
                }

                Protocol.T_BT_ACCEPT -> log("info", "bt peer ${msg.optString("pc")}")

                else -> {
                    // Certains messages (hello, pair.*) peuvent arriver hors relais.
                    if (msg.has("t") && msg.optString("t").isNotBlank()) {
                        handleAppFrame(raw)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "bad frame", t)
            log("err", "bad frame: ${t.message}")
        }
    }

    private fun handleAppFrame(raw: String) {
        try {
            val msg = JSONObject(raw)
            when (msg.optString("t")) {
                Protocol.T_HELLO -> {
                    pcName.value = msg.optString("pc", pcName.value)
                    user.value = msg.optString("user", user.value)
                    userSince.value = msg.optString("since", userSince.value)
                    log("ok", "hello from ${pcName.value}")
                }

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
                    commands.value = list
                    log("info", "${list.size} commands loaded from PC")
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

                Protocol.T_FILE_RESULT -> {
                    val ok = msg.optBoolean("ok", false)
                    log(if (ok) "ok" else "err", "file -> pc: ${msg.optString("name")} ${msg.optString("path")}")
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
            Log.e(TAG, "bad app frame", t)
            log("err", "bad frame: ${t.message}")
        }
    }

    private fun afterAuthenticated() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (status.value == LinkStatus.ONLINE) {
                delay(25_000)
                // keepalive relais (le serveur répond pong) + RTT applicatif PC
                sendRaw(JSONObject().put("t", Protocol.T_PING).toString())
                lastPingAt = System.currentTimeMillis()
                send(Protocol.T_PING, JSONObject())
            }
        }
        send(Protocol.T_COMMANDS_LIST, JSONObject())
        if (Prefs.themeSync) send(Protocol.T_THEME_GET, JSONObject())
    }

    // ------------------------------------------------------------- émissions

    private fun sendRaw(text: String): Boolean {
        if (mode == "bt") {
            val writer = btWriter ?: return false
            return writeBtLine(writer, text)
        }
        val ok = relayWs?.send(text) == true
        if (!ok) log("warn", "frame not sent (relay closed)")
        return ok
    }

    /**
     * Envoie une frame applicative : sérialisation JSON puis chiffrement
     * AES-256-GCM avec la clé de session, emballée dans une frame `relay`.
     */
    fun send(type: String, payload: JSONObject): Boolean {
        if (status.value != LinkStatus.ONLINE) {
            if (type != Protocol.T_PING) log("warn", "offline: $type not sent")
            return false
        }
        val key = sessionKey
        if (key == null) {
            log("err", "no E2E session key — re-pair the device")
            return false
        }
        val inner = JSONObject(payload.toString())
            .put("v", PROTO)
            .put("t", type)
            .toString()
        val encrypted = Crypto.b64(Crypto.aesGcmEncrypt(key, inner.toByteArray(Charsets.UTF_8)))
        val frame = JSONObject()
            .put("t", Protocol.T_RELAY)
            .put("payload", encrypted)
        return sendRaw(frame.toString())
    }

    fun runCommand(cmd: Command) {
        if (status.value != LinkStatus.ONLINE) {
            log("err", "not connected — cannot run ${cmd.id}")
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
        if (status.value != LinkStatus.ONLINE) return
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

    private fun displayName(resolver: android.content.ContentResolver, uri: android.net.Uri): String {
        resolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) return c.getString(i) ?: "file"
        }
        return uri.lastPathSegment ?: "file"
    }

    /**
     * Envoi E2E par blocs (`file.begin` / `file.chunk` / `file.end`) : le relais
     * ne voit que des frames chiffrées, jamais le contenu.
     */
    fun sendFiles(context: Context, uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        if (status.value != LinkStatus.ONLINE) {
            log("err", "not connected, file refused")
            return
        }
        busy.value = true
        scope.launch {
            var sent = 0
            uris.forEach { uri ->
                val id = UUID.randomUUID().toString()
                val name = displayName(context.contentResolver, uri)
                try {
                    send(Protocol.T_FILE_BEGIN, JSONObject().put("id", id).put("name", name))
                    var total = 0L
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val buffer = ByteArray(CHUNK)
                        while (true) {
                            val n = stream.read(buffer)
                            if (n <= 0) break
                            val data = if (n == buffer.size) buffer else buffer.copyOf(n)
                            send(
                                Protocol.T_FILE_CHUNK,
                                JSONObject().put("id", id).put("data", Crypto.b64(data))
                            )
                            total += n
                        }
                    }
                    send(Protocol.T_FILE_END, JSONObject().put("id", id).put("name", name).put("size", total))
                    sent++
                    log("ok", "file -> pc: $name ($total bytes, E2E)")
                } catch (t: Throwable) {
                    log("err", "file $name failed: ${t.message}")
                }
            }
            if (sent == uris.size && sent > 0) log("ok", "$sent file(s) transferred over E2E")
            busy.value = false
        }
    }

    /** Réception d'un fichier venant du PC (offre HTTP directe, LAN). */
    private fun receiveFile(msg: JSONObject) {
        val name = msg.optString("name", "file")
        val url = msg.optString("url", "")
        if (url.isBlank()) return
        val context = appContext ?: return
        scope.launch {
            try {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                val req = android.app.DownloadManager.Request(android.net.Uri.parse(url))
                    .setTitle(name)
                    .setDescription("OmarchyConnect")
                    .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalPublicDir(
                        android.os.Environment.DIRECTORY_DOWNLOADS,
                        name
                    )
                dm.enqueue(req)
                log("ok", "file <- pc: $name -> Download/")
            } catch (t: Throwable) {
                log("err", "receive $name failed: ${t.message}")
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun ensureDeviceId(): String {
        if (Prefs.deviceId.isBlank()) {
            val raw = ByteArray(16)
            java.security.SecureRandom().nextBytes(raw)
            Prefs.deviceId = Crypto.hex(raw)
        }
        if (Prefs.deviceName.isBlank()) Prefs.deviceName = deviceName()
        return Prefs.deviceId
    }

    private fun deviceName(): String {
        val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
        return if (Prefs.deviceName.isBlank() || Prefs.deviceName == "Android") model else Prefs.deviceName
    }
}
