package dev.omniand.hub.pairing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Persists a public device ID and protects the relay credential with a non-exportable AES key. */
class DeviceIdentity(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    val deviceId: String
        get() =
            preferences.getString(DEVICE_ID, null)
                ?: UUID.randomUUID().toString().also {
                    preferences.edit().putString(DEVICE_ID, it).apply()
                }

    fun credential(): String? {
        val encoded = preferences.getString(CREDENTIAL, null) ?: return null
        return runCatching {
                val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                require(bytes.size > IV_LENGTH)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LENGTH))
                String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
            }
            .getOrElse {
                resetEnrollment()
                null
            }
    }

    fun storeCredential(value: String) {
        require(value.matches(Regex("[0-9a-f]{64}"))) { "invalid relay credential" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        preferences
            .edit()
            .putString(CREDENTIAL, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()
    }

    fun clearCredential() {
        preferences.edit().remove(CREDENTIAL).commit()
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    fun resetEnrollment() {
        clearCredential()
        preferences.edit().remove(DEVICE_ID).commit()
    }

    private fun key(): SecretKey {
        val store = keyStore()
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val PREFERENCES = "relay-identity"
        const val DEVICE_ID = "device-id"
        const val CREDENTIAL = "credential"
        const val KEY_ALIAS = "omniand-relay-credential-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
    }
}
