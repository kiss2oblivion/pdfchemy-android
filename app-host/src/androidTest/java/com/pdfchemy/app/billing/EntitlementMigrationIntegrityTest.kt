package com.pdfchemy.app.billing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID
import javax.crypto.KeyGenerator

/** Uses the previous shipped library as an independent, test-only format oracle. */
@RunWith(AndroidJUnit4::class)
class EntitlementMigrationIntegrityTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val id = UUID.randomUUID().toString()
    private val legacyName = "migration-legacy-$id"
    private val currentName = "migration-current-$id"
    private val legacyAlias = "migration-legacy-key-$id"
    private val alias = "migration-new-key-$id"

    @After fun cleanupOnlyOwnedFixtures() {
        app.deleteSharedPreferences(legacyName); app.deleteSharedPreferences(currentName)
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        listOf(legacyAlias, alias).forEach { if (keys.containsAlias(it)) keys.deleteEntry(it) }
    }

    private fun legacy(entitled: Boolean): Map<String, *> {
        val key = MasterKey.Builder(app, legacyAlias).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val prefs = EncryptedSharedPreferences.create(app, legacyName, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        assertTrue(prefs.edit().putBoolean(BillingManager.KEY_IS_PREMIUM, entitled).commit())
        return app.getSharedPreferences(legacyName, 0).all.toMap()
    }

    private fun store() = PlatformEntitlementStore(app.getSharedPreferences(currentName, 0),
        KeystoreEntitlementCipher(alias), LegacyEntitlementReader(app, legacyName, legacyAlias)::read)

    @Test fun migratesActualLegacyEncryptedTrueAndReopensOffline() {
        val before = legacy(true)
        assertTrue(store().read()); assertTrue(store().read())
        assertEquals(before, app.getSharedPreferences(legacyName, 0).all)
        val record = app.getSharedPreferences(currentName, 0).getString(PlatformEntitlementStore.RECORD, null)!!
        assertTrue(record.startsWith("2:")); assertFalse(record.contains("true"))
        assertTrue(KeystoreEntitlementCipher(alias).decrypt(record))
    }

    @Test fun migratesActualLegacyFalseAndPersistsVerifiedChanges() {
        val before = legacy(false)
        assertFalse(store().read()); assertTrue(store().write(true)); assertTrue(store().read())
        assertTrue(store().write(false)); assertFalse(store().read())
        assertEquals(before, app.getSharedPreferences(legacyName, 0).all)
    }

    @Test fun damagedLegacyKeysetFailsClosedWithoutOverwritingOriginal() {
        legacy(true)
        val prefs = app.getSharedPreferences(legacyName, 0)
        prefs.edit().putString(LegacyEntitlementReader.KEYSET_VALUES, "not-hex").commit()
        val before = prefs.all.toMap()
        assertFalse(store().read()); assertEquals(before, prefs.all)
        assertFalse(app.getSharedPreferences(currentName, 0).contains(PlatformEntitlementStore.RECORD))
    }

    @Test fun missingLegacyKeyDoesNotRegenerateOrDestroyIt() {
        val before = legacy(true)
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.deleteEntry(legacyAlias)
        assertFalse(store().read()); assertFalse(keys.containsAlias(legacyAlias))
        assertEquals(before, app.getSharedPreferences(legacyName, 0).all)
    }

    @Test fun tamperedNewRecordCannotReviveLegacyTrue() {
        legacy(true); assertTrue(store().read())
        val prefs = app.getSharedPreferences(currentName, 0)
        val record = prefs.getString(PlatformEntitlementStore.RECORD, null)!!
        val parts = record.split(':').toMutableList()
        val ciphertext = android.util.Base64.decode(parts[2], android.util.Base64.NO_WRAP)
        ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
        parts[2] = android.util.Base64.encodeToString(ciphertext, android.util.Base64.NO_WRAP)
        prefs.edit().putString(PlatformEntitlementStore.RECORD, parts.joinToString(":")).commit()
        assertFalse(store().read())
    }

    @Test fun unavailableCurrentKeyNeverRegeneratesOnRead() {
        legacy(true); assertTrue(store().read())
        val before = app.getSharedPreferences(currentName, 0).all.toMap()
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.deleteEntry(alias)
        assertFalse(store().read()); assertFalse(keys.containsAlias(alias))
        assertEquals(before, app.getSharedPreferences(currentName, 0).all)
    }
}
