package com.pdfchemy.app.billing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EntitlementStoreTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private fun prefs() = app.getSharedPreferences("entitlement-test-${UUID.randomUUID()}", 0)
    private class TestCipher : EntitlementCipher {
        var unavailable = false
        override fun encrypt(entitled: Boolean): String {
            check(!unavailable)
            return if (entitled) "opaque-true" else "opaque-false"
        }
        override fun decrypt(record: String): Boolean {
            check(!unavailable)
            require(record == "opaque-true" || record == "opaque-false")
            return record == "opaque-true"
        }
    }

    @Test fun emptyStoreDoesNotGrantOrCreateARecord() {
        val prefs = prefs()
        assertFalse(PlatformEntitlementStore(prefs, TestCipher()) { null }.read())
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun legacyTrueMigratesOnceAndSurvivesReopening() {
        val prefs = prefs(); var reads = 0
        val store = PlatformEntitlementStore(prefs, TestCipher()) { reads++; true }
        assertTrue(store.read()); assertTrue(store.read()); assertEquals(1, reads)
        assertEquals("opaque-true", prefs.getString(PlatformEntitlementStore.RECORD, null))
        assertTrue(PlatformEntitlementStore(prefs, TestCipher()) { error("Must not reread legacy") }.read())
    }

    @Test fun verifiedRevocationDoesNotResurrectLegacyPremium() {
        val prefs = prefs(); val store = PlatformEntitlementStore(prefs, TestCipher()) { true }
        assertTrue(store.read()); assertTrue(store.write(false))
        assertFalse(PlatformEntitlementStore(prefs, TestCipher()) { true }.read())
    }

    @Test fun failedMigrationCanRetryWithoutDestroyingLegacy() {
        val prefs = prefs(); val cipher = TestCipher().apply { unavailable = true }
        val store = PlatformEntitlementStore(prefs, cipher) { true }
        assertTrue(store.read()); assertTrue(prefs.all.isEmpty())
        cipher.unavailable = false
        assertTrue(store.read()); assertTrue(prefs.contains(PlatformEntitlementStore.RECORD))
    }

    @Test fun damagedCurrentRecordFailsClosedWithoutLegacyFallback() {
        val prefs = prefs(); prefs.edit().putString(PlatformEntitlementStore.RECORD, "damaged").commit()
        assertFalse(PlatformEntitlementStore(prefs, TestCipher()) { error("Must not revive old state") }.read())
        assertEquals("damaged", prefs.getString(PlatformEntitlementStore.RECORD, null))
    }

    @Test fun legacyReadFailureDoesNotCreateOrOverwriteState() {
        val prefs = prefs(); prefs.edit().putString("unrelated", "preserve").commit()
        assertFalse(PlatformEntitlementStore(prefs, TestCipher()) { throw SecurityException("Unavailable") }.read())
        assertEquals(mapOf("unrelated" to "preserve"), prefs.all)
    }

    @Test fun writeFailureNeverStoresPlaintext() {
        val prefs = prefs(); val cipher = TestCipher().apply { unavailable = true }
        assertFalse(PlatformEntitlementStore(prefs, cipher) { null }.write(true))
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun oversizedOrWrongTypeCurrentRecordsFailClosed() {
        val prefs = prefs()
        prefs.edit().putString(PlatformEntitlementStore.RECORD, "x".repeat(257)).commit()
        assertFalse(PlatformEntitlementStore(prefs, TestCipher()) { true }.read())
        prefs.edit().putBoolean(PlatformEntitlementStore.RECORD, true).commit()
        assertFalse(PlatformEntitlementStore(prefs, TestCipher()) { true }.read())
    }

    @Test fun readsPreviousPlaintextFallbackWithoutMutatingIt() {
        val name = "legacy-${UUID.randomUUID()}"
        val legacy = app.getSharedPreferences(name, 0)
        legacy.edit().putBoolean(BillingManager.KEY_IS_PREMIUM, true).commit()
        val before = legacy.all.toMap()
        assertEquals(true, LegacyEntitlementReader(app, name).read())
        assertEquals(before, legacy.all)
    }
}
