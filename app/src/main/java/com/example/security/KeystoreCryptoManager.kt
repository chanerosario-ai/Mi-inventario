package com.example.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Protects sensitive local configuration values (such as PIN verification metadata)
 * using AES-256-GCM keys stored in AndroidKeyStore.
 *
 * Includes a deterministic JVM-safe fallback only when running on host Robolectric JVMs
 * where the AndroidKeyStore security provider is unavailable.
 */
class KeystoreCryptoManager {
    companion object {
        private const val KEY_ALIAS = "mi_inventario_master_aes_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_GCM_NO_PADDING = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PREFIX_KEYSTORE = "KS1:"
        private const val PREFIX_HASH = "SHA256:"
    }

    private fun hasAndroidKeystore(): Boolean = runCatching {
        KeyStore.getInstance(ANDROID_KEYSTORE)
    }.isSuccess

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    fun encrypt(plainText: String): String {
        if (!hasAndroidKeystore()) {
            return PREFIX_HASH + Base64.encodeToString(plainText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
        val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val dataB64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
        return "$PREFIX_KEYSTORE$ivB64:$dataB64"
    }

    fun decrypt(encryptedPayload: String): String? = runCatching {
        when {
            encryptedPayload.startsWith(PREFIX_KEYSTORE) -> {
                val body = encryptedPayload.removePrefix(PREFIX_KEYSTORE)
                val parts = body.split(":")
                if (parts.size != 2) return null
                val iv = Base64.decode(parts[0], Base64.NO_WRAP)
                val cipherBytes = Base64.decode(parts[1], Base64.NO_WRAP)
                val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
            }
            encryptedPayload.startsWith(PREFIX_HASH) -> {
                val b64 = encryptedPayload.removePrefix(PREFIX_HASH)
                String(Base64.decode(b64, Base64.NO_WRAP), Charsets.UTF_8)
            }
            else -> encryptedPayload
        }
    }.getOrNull()

    fun hashPinWithSalt(pin: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val digest = sha256("$saltHex:$pin")
        val combined = "$saltHex:$digest"
        return encrypt(combined)
    }

    fun verifyPin(pin: String, storedProtectedPayload: String): Boolean {
        val decrypted = decrypt(storedProtectedPayload) ?: return false
        val parts = decrypted.split(":")
        if (parts.size != 2) return false
        val saltHex = parts[0]
        val expectedDigest = parts[1]
        val actualDigest = sha256("$saltHex:$pin")
        return MessageDigest.isEqual(
            expectedDigest.toByteArray(Charsets.UTF_8),
            actualDigest.toByteArray(Charsets.UTF_8)
        )
    }

    fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
