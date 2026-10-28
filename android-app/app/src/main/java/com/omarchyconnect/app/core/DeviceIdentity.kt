package com.omarchyconnect.app.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyAgreement
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Identité de l'appareil stockée dans le Keystore Android (clé privée non
 * exportable) : elle sert à l'appairage et aux futurs échanges mutuellement
 * authentifiés (challenge/signature).
 *
 * Algo : EC P-256 + SHA-256, dispo dès l'API 24.
 */
object DeviceIdentity {

    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val ALIAS = "omarchy_connect_device"

    @Synchronized
    private fun keyPair(): KeyPair {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = keyStore.getKey(ALIAS, null)
        val cert = keyStore.getCertificate(ALIAS)
        if (existing != null && cert != null) {
            return KeyPair(cert.publicKey, existing as PrivateKey)
        }

        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEY_STORE
        )
        kpg.initialize(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(
                    KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA512
                )
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                .setKeySize(256)
                .build()
        )
        val kp = kpg.generateKeyPair()
        return kp
    }

    /** Clé publique encodée SubjectPublicKeyInfo (DER), en Base64 standard. */
    fun publicKeyBase64(): String {
        val cert = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            .getCertificate(ALIAS)
            ?: keyPair().let {
                KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }.getCertificate(ALIAS)
            }
        return Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
    }

    /** Empreinte SHA-256 de la clé publique (affichée dans les réglages). */
    fun fingerprint(): String {
        val der = Base64.decode(publicKeyBase64(), Base64.NO_WRAP)
        return MessageDigest.getInstance("SHA-256")
            .digest(der)
            .take(8)
            .joinToString(":") { "%02x".format(it) }
    }

    /** Signature DER d'un challenge (usage: authentification mutuelle). */
    fun sign(challenge: ByteArray): String {
        val kp = keyPair()
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(kp.private)
        s.update(challenge)
        return Base64.encodeToString(s.sign(), Base64.NO_WRAP)
    }

    fun verify(pub: ByteArray, challenge: ByteArray, signature: ByteArray): Boolean = try {
        val spec = java.security.spec.X509EncodedKeySpec(pub)
        val kf = java.security.KeyFactory.getInstance("EC")
        val s = Signature.getInstance("SHA256withECDSA")
        s.initVerify(kf.generatePublic(spec))
        s.update(challenge)
        s.verify(signature)
    } catch (_: Throwable) {
        false
    }

    @Suppress("unused")
    private fun curveName(pub: ECPublicKey): String = pub.params.curve.field.fieldSize.toString()
}
