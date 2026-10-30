package com.omarchyconnect.app.core

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Primitives cryptographiques partagées avec le daemon Rust.
 *
 *  - ECDH P-256 (dans [DeviceIdentity]) + **HKDF-SHA256** (sel fixe
 *    `omarchy-connect-v1`, info `payload-key-aes256`) → clé de session 32 octets ;
 *  - **AES-256-GCM** : IV aléatoire de 12 octets préfixé, tag de 16 octets
 *    ajouté par le provider JCE — même format que la crate Rust `aes-gcm`.
 *
 * Le serveur relais ne voit que ces blobs opaques (base64).
 */
object Crypto {

    private val random = SecureRandom()

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun b64d(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun unhex(value: String): ByteArray {
        val clean = value.trim()
        require(clean.length % 2 == 0) { "hex impair" }
        return ByteArray(clean.length / 2) {
            ((Character.digit(clean[it * 2], 16) shl 4) or Character.digit(clean[it * 2 + 1], 16)).toByte()
        }
    }

    /** HKDF-SHA256 (RFC 5869), identique à `Hkdf::<Sha256>::new(Some(salt), ikm)`. */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        val okm = ByteArray(length)
        var t = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val take = minOf(t.size, length - written)
            System.arraycopy(t, 0, okm, written, take)
            written += take
            counter++
        }
        return okm
    }

    /** Chiffre et préfixe l'IV : `iv(12) || ciphertext || tag(16)`. */
    fun aesGcmEncrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).also { random.nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val sealed = cipher.doFinal(plaintext)
        return iv + sealed
    }

    fun aesGcmDecrypt(key: ByteArray, data: ByteArray): ByteArray {
        require(data.size > 12 + 16) { "payload trop court" }
        val iv = data.copyOfRange(0, 12)
        val sealed = data.copyOfRange(12, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(sealed)
    }

    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
