package com.pdfchemy.app.ui

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HonestFeatureLocalizationTest {

    private fun context(tag: String): Context {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(app.resources.configuration)
        configuration.setLocales(LocaleList(Locale.forLanguageTag(tag)))
        return app.createConfigurationContext(configuration)
    }

    private val supportedLocales = listOf(
        "ar", "de", "es", "fr", "hi", "id", "it", "ja", "ko", "nl",
        "pl", "pt", "pt-BR", "ro", "ru", "th", "tr", "vi", "zh-CN", "zh-TW"
    )

    @Test
    fun allLocalesContainHonestLinearizeAndPdfAContracts() {
        for (tag in supportedLocales) {
            val ctx = context(tag)

            // Fast Web View keys
            val fastWebViewTitle = ctx.getString(R.string.menu_fast_web_view)
            val fastWebViewDesc = ctx.getString(R.string.menu_fast_web_view_desc)
            val linearizeSubtitle = ctx.getString(R.string.linearize_subtitle)
            val selectLinearize = ctx.getString(R.string.select_pdf_for_linearize)
            val diagnosticStatus = ctx.getString(R.string.label_diagnostic_status)
            val fastWebViewEnabled = ctx.getString(R.string.label_fast_web_view_enabled)
            val statusDisabled = ctx.getString(R.string.status_disabled)
            val btnOptimize = ctx.getString(R.string.btn_optimize_fast_web_view)

            assertTrue("Empty fastWebViewTitle for $tag", fastWebViewTitle.isNotBlank())
            assertTrue("Empty fastWebViewDesc for $tag", fastWebViewDesc.isNotBlank())
            assertTrue("Empty linearizeSubtitle for $tag", linearizeSubtitle.isNotBlank())
            assertTrue("Empty selectLinearize for $tag", selectLinearize.isNotBlank())
            assertTrue("Empty diagnosticStatus for $tag", diagnosticStatus.isNotBlank())
            assertTrue("Empty fastWebViewEnabled for $tag", fastWebViewEnabled.isNotBlank())
            assertTrue("Empty statusDisabled for $tag", statusDisabled.isNotBlank())
            assertTrue("Empty btnOptimize for $tag", btnOptimize.isNotBlank())

            // PDF/A keys
            val pdfaTitle = ctx.getString(R.string.menu_pdfa_validator)
            val pdfaDesc = ctx.getString(R.string.menu_pdfa_validator_desc)
            val pdfaHeadline = ctx.getString(R.string.pdfa_headline)
            val pdfaSubtitle = ctx.getString(R.string.pdfa_subtitle)
            val selectPdfa = ctx.getString(R.string.select_pdf_for_pdfa)

            assertTrue("Empty pdfaTitle for $tag", pdfaTitle.isNotBlank())
            assertTrue("Empty pdfaDesc for $tag", pdfaDesc.isNotBlank())
            assertTrue("Empty pdfaHeadline for $tag", pdfaHeadline.isNotBlank())
            assertTrue("Empty pdfaSubtitle for $tag", pdfaSubtitle.isNotBlank())
            assertTrue("Empty selectPdfa for $tag", selectPdfa.isNotBlank())

            // Ensure no raw XML escape artifacts leaked
            for (str in listOf(
                fastWebViewTitle, fastWebViewDesc, linearizeSubtitle, selectLinearize,
                diagnosticStatus, fastWebViewEnabled, statusDisabled, btnOptimize,
                pdfaTitle, pdfaDesc, pdfaHeadline, pdfaSubtitle, selectPdfa
            )) {
                assertFalse("Raw newline escape leaked in $tag: $str", str.contains("\\n"))
                assertFalse("Raw quote escape leaked in $tag: $str", str.contains("\\'"))
                assertFalse("Raw double quote leaked in $tag: $str", str.contains("\\\""))
            }
        }
    }

    @Test
    fun romanianStringsMatchHonestySpecificationExactly() {
        val ro = context("ro")

        assertEquals("Informații Fast Web View", ro.getString(R.string.menu_fast_web_view))
        assertEquals("Inspectează informațiile PDF; generarea Fast Web View nu este disponibilă", ro.getString(R.string.menu_fast_web_view_desc))
        assertEquals("Această versiune nu poate genera sau certifica fișiere PDF Fast Web View. Nu se va crea niciun fișier de ieșire.", ro.getString(R.string.linearize_subtitle))
        assertEquals("Selectează un PDF pentru inspectare", ro.getString(R.string.select_pdf_for_linearize))
        assertEquals("Informații PDF", ro.getString(R.string.label_diagnostic_status))
        assertEquals("Liniarizare:", ro.getString(R.string.label_fast_web_view_enabled))
        assertEquals("Neverificat", ro.getString(R.string.status_disabled))
        assertEquals("Optimizare indisponibilă", ro.getString(R.string.btn_optimize_fast_web_view))

        assertEquals("Verificări preliminare PDF/A", ro.getString(R.string.menu_pdfa_validator))
        assertEquals("Rulează verificări preliminare; conformitatea PDF/A nu este certificată", ro.getString(R.string.menu_pdfa_validator_desc))
        assertEquals("Doar verificări preliminare; necertificat", ro.getString(R.string.pdfa_headline))
        assertEquals("Aceste verificări nu certifică conformitatea ISO 19005. Conversia PDF/A nu este disponibilă în această versiune.", ro.getString(R.string.pdfa_subtitle))
        assertEquals("Selectează PDF pentru verificări preliminare", ro.getString(R.string.select_pdf_for_pdfa))
    }
}
