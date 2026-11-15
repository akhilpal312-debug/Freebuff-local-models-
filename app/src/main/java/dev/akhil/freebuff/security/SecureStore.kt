package dev.akhil.freebuff.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed storage for optional remote-provider credentials.
 *
 * Values are encrypted with an AES/GCM key that never leaves the Android
 * Keystore. Nothing is written to backups (allowBackup=false) and nothing is
 * logged. The app is fully functional without ever storing a secret here.
 */
class SecureStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("freebuff_secure", Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun put(key: String, value: String) {
        if (value.isEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(key, payload).apply()
    }

    fun get(key: String): String? {
        val payload = prefs.getString(key, null) ?: return null
        return runCatching {
            val (ivPart, dataPart) = payload.split(":", limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(ivPart, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(dataPart, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    fun remove(key: String) = prefs.edit().remove(key).apply()

    fun has(key: String): Boolean = prefs.contains(key)

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "freebuff_secure_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        const val KEY_REMOTE_API = "remote_api_key"
        const val KEY_SEARCH_API = "search_api_key"
    }
}
