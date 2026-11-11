package app.decentralert.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.decentralert.shared.EmergencyVault
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class DeviceCredentials(val deviceId: String, val deviceToken: String)

class VaultStore(context: Context) {
    private val preferences = context.getSharedPreferences("decentralert-vault", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun loadVault(): EmergencyVault? = preferences.getString("vault", null)?.let { stored ->
        val plaintext = decryptOrLegacy(stored) ?: return@let null
        runCatching { json.decodeFromString<EmergencyVault>(plaintext) }.getOrNull()?.also {
            if (!stored.startsWith(ENCRYPTED_PREFIX)) saveVault(it)
        }
    }

    fun saveVault(vault: EmergencyVault) {
        preferences.edit().putString("vault", encrypt(json.encodeToString(vault))).apply()
    }

    fun credentials(): DeviceCredentials? {
        val id = preferences.getString("device-id", null)?.let(::decryptOrLegacy) ?: return null
        val token = preferences.getString("device-token", null)?.let(::decryptOrLegacy) ?: return null
        return DeviceCredentials(id, token)
    }

    fun saveCredentials(credentials: DeviceCredentials) {
        preferences.edit()
            .putString("device-id", encrypt(credentials.deviceId))
            .putString("device-token", encrypt(credentials.deviceToken))
            .apply()
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ciphertext = Base64.encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "$ENCRYPTED_PREFIX$iv:$ciphertext"
    }

    private fun decryptOrLegacy(stored: String): String? {
        if (!stored.startsWith(ENCRYPTED_PREFIX)) return stored
        return runCatching {
            val components = stored.removePrefix(ENCRYPTED_PREFIX).split(':', limit = 2)
            require(components.size == 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                encryptionKey(),
                GCMParameterSpec(128, Base64.decode(components[0], Base64.NO_WRAP)),
            )
            cipher.doFinal(Base64.decode(components[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
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
        const val KEY_ALIAS = "decentralert-emergency-vault-v1"
        const val ENCRYPTED_PREFIX = "v1:"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
