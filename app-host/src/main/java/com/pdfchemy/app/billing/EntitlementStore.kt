package com.pdfchemy.app.billing

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.DeterministicAead
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig2026
import com.google.crypto.tink.daead.DeterministicAeadConfig2026
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface EntitlementStore {
    fun read(): Boolean
    fun write(entitled: Boolean): Boolean
}

internal object EntitlementStores {
    fun open(context: Context): EntitlementStore {
        val app = context.applicationContext ?: context
        return PlatformEntitlementStore(
            app.getSharedPreferences("billing_entitlement_v2", Context.MODE_PRIVATE),
            KeystoreEntitlementCipher("pdfchemy_billing_entitlement_v2"),
            LegacyEntitlementReader(app, BillingManager.PREFS_NAME)::read
        )
    }
}

/** Authenticated local cache only; Google Play inventory remains authoritative. */
internal class PlatformEntitlementStore(
    private val prefs: SharedPreferences,
    private val cipher: EntitlementCipher,
    private val readLegacy: () -> Boolean?
) : EntitlementStore {
    companion object {
        internal const val RECORD = "authenticated_entitlement"
        private val lock = Any()
    }

    override fun read(): Boolean = synchronized(lock) {
        if (prefs.contains(RECORD)) {
            // An invalid current record must never resurrect an old true entitlement.
            return@synchronized runCatching {
                val record = prefs.getString(RECORD, null) ?: error("Missing record")
                require(record.length <= 256)
                cipher.decrypt(record)
            }.getOrDefault(false)
        }
        val legacy = runCatching { readLegacy() }.getOrNull() ?: return@synchronized false
        // Never delete the old record/keysets. A failed migration can retry after recovery.
        write(legacy)
        legacy
    }

    override fun write(entitled: Boolean): Boolean = synchronized(lock) {
        runCatching {
            val encrypted = cipher.encrypt(entitled)
            check(encrypted.length <= 256)
            prefs.edit().putString(RECORD, encrypted).commit()
        }.getOrDefault(false)
    }
}

internal interface EntitlementCipher {
    fun encrypt(entitled: Boolean): String
    fun decrypt(record: String): Boolean
}

/** No plaintext fallback and no key regeneration while reading existing ciphertext. */
internal class KeystoreEntitlementCipher(private val alias: String) : EntitlementCipher {
    private val aad = "com.pdfchemy.app:premium_upgrade:v2".toByteArray(Charsets.UTF_8)

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) return store.getKey(alias, null) as SecretKey
        check(create) { "Entitlement key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    override fun encrypt(entitled: Boolean): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(aad)
        check(cipher.iv.size == 12)
        val encrypted = cipher.doFinal(byteArrayOf(if (entitled) 1 else 0))
        return "2:" + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    override fun decrypt(record: String): Boolean {
        require(record.length <= 256)
        val parts = record.split(':')
        require(parts.size == 3 && parts[0] == "2")
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[2], Base64.NO_WRAP)
        require(iv.size == 12 && encrypted.size == 17)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val plain = cipher.doFinal(encrypted)
        require(plain.size == 1 && plain[0] in 0..1)
        return plain[0] == 1.toByte()
    }
}

/** Read-only compatibility for the exact AndroidX alpha06 format previously shipped. */
internal class LegacyEntitlementReader(
    private val context: Context,
    private val name: String,
    private val masterAlias: String = "_androidx_security_master_key_"
) {
    companion object {
        const val KEYSET_KEYS = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
        const val KEYSET_VALUES = "__androidx_security_crypto_encrypted_prefs_value_keyset__"
    }

    fun read(): Boolean? {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        if (!prefs.contains(KEYSET_KEYS) && !prefs.contains(KEYSET_VALUES)) {
            // Previous releases could write a boolean when Keystore initialization failed.
            return if (prefs.contains(BillingManager.KEY_IS_PREMIUM))
                prefs.getBoolean(BillingManager.KEY_IS_PREMIUM, false) else null
        }
        val master = AndroidKeystoreKmsClient().getAead("android-keystore://$masterAlias")
        val keyConfig = DeterministicAeadConfig2026.get()
        val valueConfig = AeadConfig2026.get()
        fun keyset(key: String): ByteArray {
            val hex = prefs.getString(key, null) ?: error("Missing legacy keyset")
            require(hex.length in 2..32_768 && hex.length % 2 == 0)
            return ByteArray(hex.length / 2) { index ->
                val offset = index * 2
                val high = hex[offset].digitToInt(16)
                val low = hex[offset + 1].digitToInt(16)
                ((high shl 4) or low).toByte()
            }
        }
        val keyAead = TinkProtoKeysetFormat.parseEncryptedKeyset(keyset(KEYSET_KEYS), master, byteArrayOf(), keyConfig)
            .getPrimitive(keyConfig, DeterministicAead::class.java)
        val valueAead = TinkProtoKeysetFormat.parseEncryptedKeyset(keyset(KEYSET_VALUES), master, byteArrayOf(), valueConfig)
            .getPrimitive(valueConfig, Aead::class.java)
        val encryptedKey = Base64.encodeToString(keyAead.encryptDeterministically(
            BillingManager.KEY_IS_PREMIUM.toByteArray(Charsets.UTF_8), name.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        val encoded = prefs.getString(encryptedKey, null) ?: return null
        require(encoded.length <= 256)
        val value = valueAead.decrypt(Base64.decode(encoded, Base64.DEFAULT), encryptedKey.toByteArray(Charsets.UTF_8))
        require(value.size == 5)
        val buffer = ByteBuffer.wrap(value)
        require(buffer.int == 5) // AndroidX EncryptedType.BOOLEAN
        val boolean = buffer.get()
        require(boolean in 0..1)
        return boolean == 1.toByte()
    }
}
