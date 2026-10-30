package com.omarchyconnect.app.core

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Identité de l'appareil.
 *
 * Deux clés EC P-256 distinctes, dont la partie privée **ne quitte jamais**
 * l'appareil :
 *
 *  - **auth** (`omarchy_connect_device`) : signature ECDSA/SHA-256 des nonces
 *    du relais (Keystore, non exportable) ;
 *  - **dh** (`omarchy_connect_dh`) : ECDH pour dériver la clé de session
 *    E2E avec le PC. Keystore `PURPOSE_AGREE` à partir d'Android 12 ; sur les
 *    anciens appareils, repli sur une paire logicielle dont la clé privée est
 *    stockée chiffrée dans [Prefs] (EncryptedSharedPreferences).
 *
 * Sur le fil, les clés publiques sont au format **SEC1 non compressé**
 * (0x04 || X || Y, 65 octets, base64) — exactement ce qu'attend le serveur
 * (`p256::PublicKey::from_sec1_bytes`).
 */
object DeviceIdentity {

    private const val TAG = "OcIdentity"
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val AUTH_ALIAS = "omarchy_connect_device"
    private const val DH_ALIAS = "omarchy_connect_dh"
    private const val CURVE = "secp256r1"

    // ------------------------------------------------------------- clés

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    @Synchronized
    private fun authKeyPair(): KeyPair {
        val keyStore = keyStore()
        val existing = keyStore.getKey(AUTH_ALIAS, null)
        val cert = keyStore.getCertificate(AUTH_ALIAS)
        if (existing != null && cert != null) {
            return KeyPair(cert.publicKey, existing as PrivateKey)
        }

        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEY_STORE
        )
        kpg.initialize(
            KeyGenParameterSpec.Builder(
                AUTH_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(
                    KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA512
                )
                .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                .setKeySize(256)
                .build()
        )
        return kpg.generateKeyPair()
    }

    @Synchronized
    private fun dhKeyPair(): KeyPair {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val keyStore = keyStore()
                val existing = keyStore.getKey(DH_ALIAS, null)
                val cert = keyStore.getCertificate(DH_ALIAS)
                if (existing != null && cert != null) {
                    return KeyPair(cert.publicKey, existing as PrivateKey)
                }
                val kpg = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC,
                    ANDROID_KEY_STORE
                )
                kpg.initialize(
                    KeyGenParameterSpec.Builder(DH_ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                        .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                        .setKeySize(256)
                        .build()
                )
                return kpg.generateKeyPair()
            } catch (t: Throwable) {
                Log.w(TAG, "ECDH Keystore indisponible, repli logiciel : ${t.message}")
            }
        }
        return softwareDhKeyPair()
    }

    /** Repli < Android 12 : clé privée PKCS#8 chiffrée par EncryptedSharedPreferences. */
    @Synchronized
    private fun softwareDhKeyPair(): KeyPair {
        val storedPriv = Prefs.dhSoftwarePriv
        val storedPub = Prefs.dhSoftwarePub
        if (storedPriv.isNotBlank() && storedPub.isNotBlank()) {
            try {
                val kf = KeyFactory.getInstance("EC")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Crypto.b64d(storedPriv)))
                val pub = kf.generatePublic(X509EncodedKeySpec(Crypto.b64d(storedPub)))
                return KeyPair(pub, priv)
            } catch (t: Throwable) {
                Log.w(TAG, "clé DH logicielle illisible, régénération : ${t.message}")
            }
        }
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(CURVE))
        val kp = kpg.generateKeyPair()
        Prefs.dhSoftwarePriv = Crypto.b64(kp.private.encoded)
        Prefs.dhSoftwarePub = Crypto.b64(kp.public.encoded)
        return kp
    }

    // ------------------------------------------------------- encodage SEC1

    private fun sec1(publicKey: java.security.PublicKey): ByteArray {
        val ec = publicKey as ECPublicKey
        val x = fixed32(ec.w.affineX)
        val y = fixed32(ec.w.affineY)
        val out = ByteArray(65)
        out[0] = 0x04
        System.arraycopy(x, 0, out, 1, 32)
        System.arraycopy(y, 0, out, 33, 32)
        return out
    }

    private fun fixed32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(32)
        val copy = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
        System.arraycopy(copy, 0, out, 32 - copy.size, copy.size)
        return out
    }

    private fun curveParams(): ECParameterSpec {
        val params = AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec(CURVE))
        return params.getParameterSpec(ECParameterSpec::class.java)
    }

    private fun decodeSec1(value: String): ECPublicKey {
        val raw = Crypto.b64d(value)
        require(raw.size == 65 && raw[0] == 0x04.toByte()) { "clé SEC1 invalide" }
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC")
            .generatePublic(ECPublicKeySpec(ECPoint(x, y), curveParams())) as ECPublicKey
    }

    /** Clé publique d'authentification en SEC1 base64 (envoyée au relais). */
    fun publicKeyBase64(): String = Crypto.b64(sec1(authKeyPair().public))

    /** Clé publique ECDH en SEC1 base64 (envoyée au relais / au PC en BT). */
    fun dhPublicKeyBase64(): String = Crypto.b64(sec1(dhKeyPair().public))

    /** Empreinte SHA-256 courte de la clé d'authentification (affichée dans l'UI). */
    fun fingerprint(): String =
        Crypto.sha256(sec1(authKeyPair().public)).take(8).joinToString(":") { "%02x".format(it) }

    /** Signature ECDSA/SHA-256 au format DER, base64 (vérifiée par `p256`). */
    fun sign(message: String): String = sign(message.toByteArray(Charsets.UTF_8))

    fun sign(message: ByteArray): String {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(authKeyPair().private)
        s.update(message)
        return Crypto.b64(s.sign())
    }

    /** Secret ECDH brut (coordonnée X, 32 octets) avec la clé SEC1 du pair. */
    fun ecdh(peerSec1B64: String): ByteArray {
        val agreement = javax.crypto.KeyAgreement.getInstance("ECDH")
        agreement.init(dhKeyPair().private)
        agreement.doPhase(decodeSec1(peerSec1B64), true)
        return agreement.generateSecret()
    }

    /**
     * K = HKDF-SHA256(ECDH(privée DH locale, publique DH du PC)).
     * Même dérivation que `Identity::derive_session_key` côté Rust.
     */
    fun sessionKey(peerSec1B64: String): ByteArray {
        val shared = ecdh(peerSec1B64)
        return Crypto.hkdfSha256(
            ikm = shared,
            salt = "omarchy-connect-v1".toByteArray(Charsets.UTF_8),
            info = "payload-key-aes256".toByteArray(Charsets.UTF_8),
            length = 32
        )
    }
}
