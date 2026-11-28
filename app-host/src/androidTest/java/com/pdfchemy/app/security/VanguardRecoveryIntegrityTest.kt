package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.logic.PdfGateway
import com.pdfchemy.app.logic.PdfRepairEngine
import com.pdfchemy.app.logic.PdfSanitizerEngine
import com.pdfchemy.app.logic.RepairApplyContract
import com.pdfchemy.app.logic.SanitizerCleanContract
import com.pdfchemy.app.logic.StandardOutputContract
import com.pdfchemy.app.logic.VanguardThreatResult
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionJavaScript
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VanguardRecoveryIntegrityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun fixture(file: File, active: Boolean = false, encrypted: Boolean = false) {
        PDFBoxResourceLoader.init(context)
        PDDocument().use { document ->
            repeat(2) { document.addPage(PDPage()) }
            document.documentInformation.author = "Recovery fixture"
            if (active) document.documentCatalog.openAction = PDActionJavaScript("app.alert('fixture')")
            if (encrypted) {
                val policy = StandardProtectionPolicy("fixture-owner", "fixture-user", AccessPermission())
                policy.encryptionKeyLength = 256
                document.protect(policy)
            }
            document.save(file)
        }
    }

    private suspend fun withFiles(block: suspend (File, File) -> Unit) {
        val directory = File(context.cacheDir, "vanguard-test-" + java.util.UUID.randomUUID())
        check(directory.mkdirs())
        try {
            block(File(directory, "source.pdf"), File(directory, "target.pdf"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private suspend fun expectWorkerRejection(code: Int, operation: suspend () -> Unit) {
        try {
            operation()
            fail("An unverified recovery must not succeed")
        } catch (error: PdfGateway.WorkerFailure) {
            assertEquals(code, error.errorCode)
        }
    }

    private suspend fun assertCleanOutput(output: File) {
        assertEquals(VanguardThreatResult.Clean,
            PdfSanitizerEngine.checkVanguardThreat(context, Uri.fromFile(output)))
        PDDocument.load(output).use { document ->
            assertEquals(2, document.numberOfPages)
            assertFalse(document.isEncrypted)
            assertEquals("Recovery fixture", document.documentInformation.author)
        }
    }

    @Test fun missingEofCanBeRebuiltWithoutChangingOriginal() = runBlocking<Unit> {
        withFiles { source, output ->
            fixture(source)
            val bytes = source.readBytes()
            val marker = String(bytes, Charsets.ISO_8859_1).lastIndexOf("%%EOF")
            assertTrue(marker > 0)
            val damaged = bytes.copyOf(marker)
            source.writeBytes(damaged)
            val diagnosis = PdfRepairEngine.diagnosePdf(context, Uri.fromFile(source)).getOrThrow()
            assertFalse(diagnosis.hasValidEof)
            assertEquals(damaged.size.toLong(), diagnosis.sourceBytes)
            assertNotNull(diagnosis.parserOpened)
            assertTrue(requireNotNull(diagnosis.diagnosticElapsedMs) >= 0L)
            if (diagnosis.parserOpened == true) assertNotNull(diagnosis.pooledObjects)
            val result = PdfGateway.executeEngineTyped<RepairApplyContract>(context, "REPAIR_APPLY",
                Uri.fromFile(source), Uri.fromFile(output), """{"requireVanguardClean":true}""")
            assertEquals(2, result.recoveredPages)
            val accounting = requireNotNull(result.recovery)
            assertEquals(2, accounting.readableInputPages)
            assertEquals(2, accounting.verifiedOutputPages)
            assertEquals(0, accounting.activeFindingsAfter)
            assertEquals(com.pdfchemy.app.logic.PageStreamEvidence(2, 0, 0), accounting.streamEvidence)
            assertArrayEquals(damaged, source.readBytes())
            assertCleanOutput(output)
        }
    }

    @Test fun structuralRepairCannotPublishActiveContent() = runBlocking<Unit> {
        withFiles { source, output ->
            fixture(source, active = true)
            val original = source.readBytes()
            val sentinel = "Unchanged destination".toByteArray()
            output.writeBytes(sentinel)
            assertTrue(PdfSanitizerEngine.checkVanguardThreat(context, Uri.fromFile(source))
                is VanguardThreatResult.ExecutableThreat)
            expectWorkerRejection(400) {
                PdfGateway.executeEngineTyped<RepairApplyContract>(context, "REPAIR_APPLY",
                    Uri.fromFile(source), Uri.fromFile(output), """{"requireVanguardClean":true}""")
            }
            assertArrayEquals(original, source.readBytes())
            assertArrayEquals(sentinel, output.readBytes())
        }
    }

    @Test fun sanitizationReopensCandidateAndPreservesOriginalAndMetadata() = runBlocking<Unit> {
        withFiles { source, output ->
            fixture(source, active = true)
            val original = source.readBytes()
            val report = PdfGateway.executeEngineTyped<SanitizerCleanContract>(context, "SANITIZE_CLEAN",
                Uri.fromFile(source), Uri.fromFile(output),
                """{"purgeJs":true,"purgeActions":true,"purgeMetadata":false,"purgeAttachments":false,"requireVanguardClean":true}""")
            assertTrue(report.isSuccess)
            assertTrue(report.jsRemoved > 0)
            assertTrue(requireNotNull(report.recovery?.activeFindingsBefore) > 0)
            assertEquals(0, report.recovery?.activeFindingsAfter)
            assertArrayEquals(original, source.readBytes())
            assertCleanOutput(output)
        }
    }

    @Test fun wrongPasswordHasSpecificCodeAndCannotPublish() = runBlocking<Unit> {
        withFiles { source, output ->
            fixture(source, encrypted = true)
            val original = source.readBytes()
            val sentinel = "Unchanged destination".toByteArray()
            output.writeBytes(sentinel)
            expectWorkerRejection(401) {
                PdfGateway.executeEngineTyped<StandardOutputContract>(context, "UNLOCK",
                    Uri.fromFile(source), Uri.fromFile(output),
                    """{"password":"wrong","requireVanguardClean":true,"allowActiveContentRemoval":true}""")
            }
            assertArrayEquals(original, source.readBytes())
            assertArrayEquals(sentinel, output.readBytes())
        }
    }

    @Test fun encryptedActiveContentRequiresSeparateConsent() = runBlocking<Unit> {
        withFiles { source, output ->
            fixture(source, active = true, encrypted = true)
            val original = source.readBytes()
            val sentinel = "Unchanged destination".toByteArray()
            output.writeBytes(sentinel)
            assertTrue(PdfSanitizerEngine.checkVanguardThreat(context, Uri.fromFile(source))
                is VanguardThreatResult.EncryptedCannotVerify)
            expectWorkerRejection(400) {
                PdfGateway.executeEngineTyped<StandardOutputContract>(context, "UNLOCK",
                    Uri.fromFile(source), Uri.fromFile(output),
                    """{"password":"fixture-user","requireVanguardClean":true,"allowActiveContentRemoval":false}""")
            }
            assertArrayEquals(original, source.readBytes())
            assertArrayEquals(sentinel, output.readBytes())
        }
    }

    @Test fun authorizedUnlockSanitizesAndReauditsCopy() = runBlocking<Unit> {
        withFiles { source, output ->
            fixture(source, active = true, encrypted = true)
            val original = source.readBytes()
            val params = JSONObject().put("password", "fixture-user")
                .put("requireVanguardClean", true).put("allowActiveContentRemoval", true)
            val result = PdfGateway.executeEngineTyped<StandardOutputContract>(context, "UNLOCK",
                Uri.fromFile(source), Uri.fromFile(output), params.toString())
            assertTrue(result.success)
            assertTrue(requireNotNull(result.recovery?.activeFindingsBefore) > 0)
            assertEquals(0, result.recovery?.activeFindingsAfter)
            assertArrayEquals(original, source.readBytes())
            assertCleanOutput(output)
        }
    }

    @Test fun damagedStartxrefCanBeRebuiltByExistingParserWithoutInventingPages() = runBlocking<Unit> {
        withFiles { source, output ->
            fixture(source)
            val original = source.readBytes()
            val text = String(original, Charsets.ISO_8859_1)
            val match = requireNotNull(Regex("startxref\\s+(\\d+)\\s+%%EOF").findAll(text).lastOrNull())
            val offset = requireNotNull(match.groups[1]).range
            val damaged = original.copyOf()
            for (index in offset) damaged[index] = '0'.code.toByte()
            source.writeBytes(damaged)
            val before = PdfRepairEngine.diagnosePdf(context, Uri.fromFile(source)).getOrThrow()
            assertEquals(com.pdfchemy.app.logic.XrefObservationKind.UNRECOGNIZED_TARGET, before.xref?.kind)
            assertEquals(0L, before.xref?.offset)
            val result = PdfGateway.executeEngineTyped<RepairApplyContract>(context, "REPAIR_APPLY",
                Uri.fromFile(source), Uri.fromFile(output), """{"requireVanguardClean":true}""")
            assertEquals(2, result.recoveredPages)
            assertArrayEquals(damaged, source.readBytes())
            assertCleanOutput(output)
            val after = PdfRepairEngine.diagnosePdf(context, Uri.fromFile(output)).getOrThrow()
            assertTrue(after.xref?.kind in setOf(com.pdfchemy.app.logic.XrefObservationKind.TABLE_MARKER,
                com.pdfchemy.app.logic.XrefObservationKind.INDIRECT_OBJECT_MARKER))
        }
    }
}
