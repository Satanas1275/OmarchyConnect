package com.omarchyconnect.app.core

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stockage local chiffré (Android Keystore).
 *
 * Contient uniquement ce qui est sensible :
 *  - la clé de session E2E (hex) et l'état d'appairage
 *  - l'identifiant de l'appareil et l'adresse du PC Bluetooth
 *
 * Rien n'est sauvegardé en clair dans les préférences système, et
 * `allowBackup=false` dans le manifest empêche toute extraction via ADB backup.
 */
object Prefs {

    /** Relais public (reverse proxy TLS) ; le dev local utilise `http://10.0.2.2:6716`. */
    const val DEFAULT_RELAY = "https://omarchy-connect.rocknite-studio.com"

    private const val TAG = "OcPrefs"
    private const val FILE = "omarchy_connect_secure"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context.applicationContext,
                FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Keystore indisponible, repli sur préférences standard: ${t.message}")
            context.applicationContext.getSharedPreferences("${FILE}_fallback", Context.MODE_PRIVATE)
        }
    }

    private fun put(key: String, value: String?) {
        sp.edit().putString(key, value).apply()
    }

    private fun get(key: String, def: String? = null): String? = sp.getString(key, def)

    private fun putBool(key: String, value: Boolean) {
        sp.edit().putBoolean(key, value).apply()
    }

    private fun getBool(key: String, def: Boolean): Boolean = sp.getBoolean(key, def)

    // --- Session ---

    /** Hôte affiché (domaine du relais ou nom du PC en Bluetooth). */
    var domain: String
        get() = get("domain") ?: ""
        set(v) = put("domain", v.trim().lowercase())

    /** URL de base du relais : prod par défaut, `http://10.0.2.2:6716` en dev. */
    var relayBase: String
        get() = get("relay_base") ?: DEFAULT_RELAY
        set(v) = put("relay_base", v.trim().trimEnd('/'))

    var deviceId: String
        get() = get("device_id") ?: ""
        set(v) = put("device_id", v)

    var deviceName: String
        get() = get("device_name") ?: "Android"
        set(v) = put("device_name", v)

    /** Ancien jeton direct (LAN) — conservé pour compatibilité de migration. */
    var token: String
        get() = get("token") ?: ""
        set(v) = put("token", v)

    /** `relay` | `bt` : transport utilisé pour le dernier appairage réussi. */
    var transport: String
        get() = get("transport") ?: "relay"
        set(v) = put("transport", v)

    var peerId: String
        get() = get("peer_id") ?: ""
        set(v) = put("peer_id", v)

    var pcNamePref: String
        get() = get("pc_name") ?: ""
        set(v) = put("pc_name", v)

    /** Clé de session E2E (hex 32 octets), chiffrée au repos. */
    var sessionKey: String
        get() = get("session_key") ?: ""
        set(v) = put("session_key", v)

    var btAddress: String
        get() = get("bt_address") ?: ""
        set(v) = put("bt_address", v)

    var btName: String
        get() = get("bt_name") ?: ""
        set(v) = put("bt_name", v)

    /** Repli DH logiciel (appareils < Android 12) : PKCS#8 + X.509 en base64. */
    var dhSoftwarePriv: String
        get() = get("dh_sw_priv") ?: ""
        set(v) = put("dh_sw_priv", v)

    var dhSoftwarePub: String
        get() = get("dh_sw_pub") ?: ""
        set(v) = put("dh_sw_pub", v)

    var userName: String
        get() = get("user_name") ?: "user"
        set(v) = put("user_name", v)

    var userSince: String
        get() = get("user_since") ?: "2026"
        set(v) = put("user_since", v)

    var paired: Boolean
        get() = getBool("paired", false)
        set(v) = putBool("paired", v)

    /** Empreinte (SHA-256) du certificat/clé du PC, vérifiée à chaque connexion. */
    var pcFingerprint: String
        get() = get("pc_fp") ?: ""
        set(v) = put("pc_fp", v)

    // --- Réglages ---

    var clipboardSync: Boolean
        get() = getBool("clipboard_sync", true)
        set(v) = putBool("clipboard_sync", v)

    var forwardNotifications: Boolean
        get() = getBool("forward_notifications", true)
        set(v) = putBool("forward_notifications", v)

    var themeSync: Boolean
        get() = getBool("theme_sync", true)
        set(v) = putBool("theme_sync", v)

    var remoteInput: Boolean
        get() = getBool("remote_input", true)
        set(v) = putBool("remote_input", v)

    val isKeyUsed: Boolean
        get() = sp.contains("token") || sp.contains("device_id")

    fun clear() {
        sp.edit().clear().apply()
    }
}
