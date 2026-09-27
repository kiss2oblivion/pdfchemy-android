package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PdfSecurityAndImageExportTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
    }

    private fun createSamplePdf(file: File, pageCount: Int = 2) {
        val document = PDDocument()
        for (i in 0 until pageCount) {
            val page = PDPage()
            document.addPage(page)
            val contentStream = PDPageContentStream(document, page)
            contentStream.beginText()
            contentStream.setFont(PDType1Font.HELVETICA_BOLD, 16f)
            contentStream.newLineAtOffset(50f, 700f)
            contentStream.showText("Security Test Page ${i + 1}")
            contentStream.endText()
            contentStream.close()
        }
        document.save(file)
        document.close()
    }

    @Test
    fun testProtectPdfAndUnlockPdf() = runBlocking {
        val srcFile = File(context.cacheDir, "unprotected.pdf")
        val protectedFile = File(context.cacheDir, "protected.pdf")
        val unlockedFile = File(context.cacheDir, "unlocked.pdf")
        
        createSamplePdf(srcFile, pageCount = 2)

        // 1. Initially unprotected
        val isProtectedInitially = PdfManipulator.isPdfPasswordProtected(context, Uri.fromFile(srcFile))
        assertFalse("Source PDF should not be protected initially", isProtectedInitially)

        // 2. Protect with password
        val password = "SecretPass123!"
        PdfManipulator.protectPdf(
            context = context,
            sourceUri = Uri.fromFile(srcFile),
            destUri = Uri.fromFile(protectedFile),
            userPassword = password
        )

        assertTrue("Protected file should exist", protectedFile.exists() && protectedFile.length() > 0)
        val isProtectedAfter = PdfManipulator.isPdfPasswordProtected(context, Uri.fromFile(protectedFile))
        assertTrue("Protected file should be detected as encrypted", isProtectedAfter)

        // Opening without password must fail
        var threwInvalidPassword = false
        try {
            val doc = PDDocument.load(protectedFile, "")
            if (doc.isEncrypted) {
                threwInvalidPassword = true
            }
            doc.close()
        } catch (e: InvalidPasswordException) {
            threwInvalidPassword = true
        } catch (e: Exception) {
            threwInvalidPassword = true
        }
        assertTrue("Opening protected PDF without password must throw/indicate encryption", threwInvalidPassword)

        // 3. Unlock with correct password
        PdfManipulator.unlockPdf(
            context = context,
            sourceUri = Uri.fromFile(protectedFile),
            destUri = Uri.fromFile(unlockedFile),
            password = password
        )

        assertTrue("Unlocked file should exist", unlockedFile.exists() && unlockedFile.length() > 0)
        val isProtectedFinal = PdfManipulator.isPdfPasswordProtected(context, Uri.fromFile(unlockedFile))
        assertFalse("Unlocked PDF should no longer be encrypted", isProtectedFinal)

        // Document should open cleanly without password
        val finalDoc = PDDocument.load(unlockedFile)
        assertFalse("Document should not be encrypted", finalDoc.isEncrypted)
        assertEquals(2, finalDoc.numberOfPages)
        finalDoc.close()
    }

    @Test
    fun testUnlockWithWrongPasswordFails() = runBlocking {
        val srcFile = File(context.cacheDir, "wrong_pass_src.pdf")
        val protectedFile = File(context.cacheDir, "wrong_pass_protected.pdf")
        val unlockedFile = File(context.cacheDir, "wrong_pass_unlocked.pdf")
        
        createSamplePdf(srcFile, pageCount = 1)

        val correctPass = "CorrectPassword456"
        PdfManipulator.protectPdf(
            context = context,
            sourceUri = Uri.fromFile(srcFile),
            destUri = Uri.fromFile(protectedFile),
            userPassword = correctPass
        )

        var failed = false
        try {
            PdfManipulator.unlockPdf(
                context = context,
                sourceUri = Uri.fromFile(protectedFile),
                destUri = Uri.fromFile(unlockedFile),
                password = "WrongPassword789"
            )
        } catch (e: Exception) {
            failed = true
        }
        assertTrue("Unlocking with wrong password must throw an exception", failed)
    }

    @Test
    fun testProtectPdf256BitEncryption() = runBlocking {
        val srcFile = File(context.cacheDir, "src_256.pdf")
        val destFile = File(context.cacheDir, "dest_256.pdf")
        val unlockedFile = File(context.cacheDir, "unlocked_256.pdf")

        createSamplePdf(srcFile, pageCount = 2)

        val pass = "Enterprise256Pass!"
        PdfManipulator.protectPdf(
            context = context,
            sourceUri = Uri.fromFile(srcFile),
            destUri = Uri.fromFile(destFile),
            userPassword = pass,
            keyLength = 256
        )

        assertTrue(destFile.exists())
        assertTrue(PdfManipulator.isPdfPasswordProtected(context, Uri.fromFile(destFile)))

        PdfManipulator.unlockPdf(
            context = context,
            sourceUri = Uri.fromFile(destFile),
            destUri = Uri.fromFile(unlockedFile),
            password = pass
        )

        val doc = PDDocument.load(unlockedFile)
        assertFalse(doc.isEncrypted)
        assertEquals(2, doc.numberOfPages)
        doc.close()
    }
}
