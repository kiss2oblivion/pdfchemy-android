package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PdfFlattenEngineTest {

    private lateinit var context: Context
    private lateinit var sampleFormPdf: File
    private lateinit var sampleFormUri: Uri

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)

        sampleFormPdf = File(context.cacheDir, "sample_form.pdf")
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)

        val acroForm = PDAcroForm(doc)
        doc.documentCatalog.acroForm = acroForm

        val textField = PDTextField(acroForm)
        textField.partialName = "FullName"
        acroForm.fields.add(textField)

        doc.save(sampleFormPdf)
        doc.close()
        sampleFormUri = Uri.fromFile(sampleFormPdf)
    }

    @Test
    fun testInspectAndFlattenPdf() = runBlocking {
        val diagResult = PdfFlattenEngine.inspectFlattenElements(context, sampleFormUri)
        assertTrue(diagResult.isSuccess)
        val diag = diagResult.getOrThrow()
        assertEquals(1, diag.fieldCount)

        val destFile = File(context.cacheDir, "flattened_test_out.pdf")
        val destUri = Uri.fromFile(destFile)

        val flattenResult = PdfFlattenEngine.flattenPdf(
            context = context,
            sourcePdfUri = sampleFormUri,
            destPdfUri = destUri,
            flattenForms = true,
            flattenAnnotations = true
        )

        assertTrue(flattenResult.isSuccess)
        assertTrue(destFile.exists() && destFile.length() > 0)
    }
}
