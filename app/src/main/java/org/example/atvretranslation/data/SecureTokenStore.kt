package org.example.atvretranslation.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureTokenStore(context: Context) {
    private val preferences = context.getSharedPreferences("secure_oauth", Context.MODE_PRIVATE)

    @Synchronized
    fun read(): OAuthTokens? {
        val encoded = preferences.getString(KEY_PAYLOAD, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val ivLength = bytes.first().toInt() and 0xff
            require(ivLength in 12..16 && bytes.size > ivLength + 1)
            val iv = bytes.copyOfRange(1, ivLength + 1)
            val ciphertext = bytes.copyOfRange(ivLength + 1, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val json = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
            OAuthTokens(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                expiresAtEpochSeconds = json.getLong("expires_at"),
            )
        }.getOrElse {
            clear()
            null
        }
    }

    @Synchronized
    fun write(tokens: OAuthTokens) {
        val json = JSONObject()
            .put("access_token", tokens.accessToken)
            .put("refresh_token", tokens.refreshToken)
            .put("expires_at", tokens.expiresAtEpochSeconds)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(json)
        val payload = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted
        preferences.edit().putString(KEY_PAYLOAD, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    @Synchronized
    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_PAYLOAD = "oauth_payload"
        const val KEY_ALIAS = "animelib_tv_oauth"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
