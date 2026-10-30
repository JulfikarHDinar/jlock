package com.jlock.app

import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * Turns a user secret (PIN / password / pattern-as-string) into a stored verifier that is safe at rest:
 *   plaintext = salt(16) || PBKDF2-HMAC-SHA256(secret, salt)   -- one-way
 *   stored    = AES-256-GCM(plaintext) under a hardware Android Keystore key (non-exportable)
 *
 * So an attacker with the prefs file cannot even attempt an offline brute force: the Keystore key is
 * bound to this device's hardware. jLock never stores the secret itself.
 */
object CryptoManager {
    private const val KEY_ALIAS = "jlock_master_key"
    // The real protection at rest is the hardware Keystore AES-GCM wrapping: without this device's
    // non-exportable key, the verifier can't be brute-forced offline at all, so PBKDF2 only hardens the
    // rooted on-device case (where the escalating lockout is the primary defense). This count is chosen
    // to stay fast on the unlock hot path; the verifier records the count it was made with, so it can be
    // raised later without invalidating PINs already set (see the "iters:iv:ct" format below).
    private const val ITERATIONS = 120_000
    private const val LEGACY_ITERATIONS = 120_000
    private const val HASH_BITS = 256
    private const val SALT_LEN = 16

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    private fun pbkdf2(secret: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(secret, salt, iterations, HASH_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    /** Produces the stored verifier string "iterations:iv:ciphertext". */
    fun hashSecret(secret: String): String {
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(secret.toCharArray(), salt, ITERATIONS)
        val plain = salt + hash
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val ct = cipher.doFinal(plain)
        plain.fill(0)
        return "$ITERATIONS:" + b64(cipher.iv) + ":" + b64(ct)
    }

    /** Constant-time check of [secret] against a stored verifier. False on any tampering/error.
     *  Accepts both the current "iters:iv:ct" format and the legacy "iv:ct", so raising the iteration
     *  count never locks a user out of a PIN they already set. */
    fun verifySecret(secret: String, stored: String): Boolean {
        return try {
            val parts = stored.split(":")
            val iters: Int
            val iv: ByteArray
            val ct: ByteArray
            when (parts.size) {
                3 -> { iters = parts[0].toInt(); iv = unb64(parts[1]); ct = unb64(parts[2]) }
                2 -> { iters = LEGACY_ITERATIONS; iv = unb64(parts[0]); ct = unb64(parts[1]) }
                else -> return false
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(ct)
            val salt = plain.copyOfRange(0, SALT_LEN)
            val hash = plain.copyOfRange(SALT_LEN, plain.size)
            val calc = pbkdf2(secret.toCharArray(), salt, iters)
            val ok = constantTimeEquals(hash, calc)
            plain.fill(0); hash.fill(0); calc.fill(0)
            ok
        } catch (_: Throwable) {
            false
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** [verifySecret] off the main thread (one check at a time); [done] runs on the main thread. A null [stored]
     *  (method not set) fails. Used by every lock screen so none of them stalls while the key is derived. */
    fun verifyAsync(secret: String, stored: String?, done: (Boolean) -> Unit) {
        worker.execute {
            val ok = stored != null && verifySecret(secret, stored)
            main.post { done(ok) }
        }
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].toInt() xor b[i].toInt())
        return r == 0
    }
}
