package com.pdfchemy.app.security

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppOwnedProviderAdmissionTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun alignContextWithInstalledPackageUid() {
        // Robolectric leaves Context.applicationInfo.uid unset while its package
        // manager assigns an installed UID. Real Android reports the same UID.
        val installedUid = app.packageManager.getApplicationInfo(app.packageName, 0).uid
        assertTrue(installedUid >= 0)
        app.applicationInfo.uid = installedUid
    }

    @Test fun appOwnedProviderIsRejectedForEveryExternalActionAndForgedGrantFlag() {
        val authority = "${app.packageName}.fileprovider"
        val provider = app.packageManager.resolveContentProvider(authority, 0)!!
        assertEquals(app.applicationInfo.uid, provider.applicationInfo.uid)
        val uri = Uri.parse("content://$authority/internal_scans/private.pdf")
        for (action in listOf(Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            for (flags in listOf(0, Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
                val intent = Intent(action).setFlags(flags)
                when (action) {
                    Intent.ACTION_VIEW -> intent.data = uri
                    Intent.ACTION_SEND -> intent.putExtra(Intent.EXTRA_STREAM, uri)
                    else -> intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri))
                }
                assertTrue("An Intent flag is not authority to claim an app-owned provider",
                    ExternalUriAdmission.admit(app, intent) is ExternalUriAdmission.AdmissionResult.Rejected)
            }
        }
    }

    @Test fun rawAndEncodedUserQualifiedOwnedAuthoritiesAreRejected() {
        val authority = "${app.packageName}.fileprovider"
        assertNotNull(app.packageManager.resolveContentProvider(authority, 0))
        for (encoded in listOf("0@", "0%40")) {
            val uri = Uri.parse("content://$encoded$authority/internal_scans/private.pdf")
            assertEquals("0@$authority", uri.authority)
            assertTrue(ExternalUriAdmission.admitUri(app, uri) is ExternalUriAdmission.AdmissionResult.Rejected)
        }
    }

    @Test fun ownershipUsesInstalledProviderIdentityRatherThanFileProviderNameSuffix() {
        val providers = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PROVIDERS).providers.orEmpty()
        assertTrue(providers.isNotEmpty())
        for (provider in providers) {
            assertEquals(app.applicationInfo.uid, provider.applicationInfo.uid)
            for (authority in provider.authority.split(';')) {
                assertTrue("All installed app-owned providers are private external capabilities: $authority",
                    ExternalUriAdmission.admitUri(app, Uri.parse("content://$authority/input")) is ExternalUriAdmission.AdmissionResult.Rejected)
            }
        }
    }
}
