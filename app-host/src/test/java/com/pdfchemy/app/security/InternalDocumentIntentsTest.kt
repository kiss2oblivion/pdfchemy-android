package com.pdfchemy.app.security

import android.app.Application
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.logic.DocumentActions
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InternalDocumentIntentsTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ownUri get() = Uri.parse("content://${app.packageName}.fileprovider/internal_scans/private.pdf")
    private val alias get() = ComponentName(app.packageName, InternalDocumentIntents.ALIAS_CLASS_NAME)

    @Before fun alignInstalledUid() {
        app.applicationInfo.uid = app.packageManager.getApplicationInfo(app.packageName, 0).uid
        assertTrue(app.applicationInfo.uid >= 0)
    }

    @Test fun ownDocumentUsesExplicitNonExportedAlias() {
        val intent = InternalDocumentIntents.create(app, ownUri)
        assertEquals(alias, intent.component)
        assertEquals(ownUri, InternalDocumentIntents.extractTrustedUri(app, intent))
        val info = app.packageManager.getActivityInfo(alias, 0)
        assertFalse(info.exported)
        assertEquals("com.pdfchemy.app.MainActivity", info.targetActivity)
    }

    @Test fun publicComponentAndForgedExtrasDoNotConferInternalTrust() {
        val intent = Intent(Intent.ACTION_VIEW, ownUri)
            .setComponent(ComponentName(app.packageName, "com.pdfchemy.app.MainActivity"))
            .putExtra("internal", true).putExtra("isScreenshotRun", true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertNull(InternalDocumentIntents.extractTrustedUri(app, intent))
        assertTrue(ExternalUriAdmission.admit(app, intent) is ExternalUriAdmission.AdmissionResult.Rejected)
    }

    @Test fun internalRouteRequiresOwnProviderAndViewAction() {
        val external = Uri.parse("content://foreign.provider/document/1")
        assertThrows(IllegalArgumentException::class.java) { InternalDocumentIntents.create(app, external) }
        assertNull(InternalDocumentIntents.extractTrustedUri(app, Intent(Intent.ACTION_VIEW, external).setComponent(alias)))
        assertNull(InternalDocumentIntents.extractTrustedUri(app, Intent(Intent.ACTION_SEND, ownUri).setComponent(alias)))
        assertNull(InternalDocumentIntents.extractTrustedUri(app, Intent(Intent.ACTION_VIEW, Uri.parse("file:///data/private.pdf")).setComponent(alias)))
    }

    @Test fun documentOpenRoutesOwnProviderThroughPrivateAlias() {
        DocumentActions.view(app, ownUri)
        val launched = shadowOf(app).nextStartedActivity
        assertNotNull(launched)
        assertEquals(alias, launched.component)
        assertEquals(ownUri, launched.data)
        assertEquals(Intent.ACTION_VIEW, launched.action)
    }

    @Test fun audioOutputRetainsExternalViewerAndReadGrant() {
        val audio = Uri.parse("content://${app.packageName}.fileprovider/internal_scans/audio.wav")
        // This checks viewer routing and grants, independent of FileProvider's
        // Android canonical-path checks against the Windows Robolectric filesystem.
        val resolver = mockk<ContentResolver>()
        every { resolver.getType(audio) } returns "audio/wav"
        every { resolver.getStreamTypes(audio, any()) } returns null
        val context = mockk<Context>()
        every { context.contentResolver } returns resolver
        val captured = slot<Intent>()
        every { context.startActivity(capture(captured)) } just Runs
        DocumentActions.view(context, audio)
        val launched = captured.captured
        assertNull("Audio must not be sent to the PDF reader", launched.component)
        assertEquals(Intent.ACTION_VIEW, launched.action)
        assertEquals(audio, launched.data)
        assertEquals(audio, launched.clipData!!.getItemAt(0).uri)
        assertTrue(launched.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
}
