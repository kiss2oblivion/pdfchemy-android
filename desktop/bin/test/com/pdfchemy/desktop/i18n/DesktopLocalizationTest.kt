package com.pdfchemy.desktop.i18n

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DesktopLocalizationTest {

    @After
    fun tearDown() {
        DesktopLocalization.setDefaultLanguage(DesktopLanguage.EN)
    }

    @Test
    fun testLanguageFromCodeResolution() {
        assertEquals(DesktopLanguage.EN, DesktopLanguage.fromCode("en"))
        assertEquals(DesktopLanguage.DE, DesktopLanguage.fromCode("de"))
        assertEquals(DesktopLanguage.ES, DesktopLanguage.fromCode("es"))
        assertEquals(DesktopLanguage.FR, DesktopLanguage.fromCode("fr"))
        assertEquals(DesktopLanguage.RO, DesktopLanguage.fromCode("ro"))
        assertEquals(DesktopLanguage.PT_BR, DesktopLanguage.fromCode("pt-BR"))
        assertEquals(DesktopLanguage.PT_BR, DesktopLanguage.fromCode("pt_BR"))
        assertEquals(DesktopLanguage.ZH_CN, DesktopLanguage.fromCode("zh-CN"))
        assertEquals(DesktopLanguage.ZH_TW, DesktopLanguage.fromCode("zh_TW"))
        assertEquals(DesktopLanguage.AR, DesktopLanguage.fromCode("ar"))
        assertEquals(DesktopLanguage.JA, DesktopLanguage.fromCode("ja"))

        // Unknown code falls back to English safely
        assertEquals(DesktopLanguage.EN, DesktopLanguage.fromCode("unknown_xyz"))
        assertEquals(DesktopLanguage.EN, DesktopLanguage.fromCode(null))
        assertEquals(DesktopLanguage.EN, DesktopLanguage.fromCode(""))
    }

    @Test
    fun testAll21LocalesHaveCompleteNonEmptyStrings() {
        DesktopLanguage.entries.forEach { lang ->
            val strings = DesktopStringStore.getStrings(lang)
            assertNotNull("Strings should not be null for $lang", strings)
            assertTrue("appTitle should not be blank for $lang", strings.appTitle.isNotBlank())
            assertTrue("desktopEdition should not be blank for $lang", strings.desktopEdition.isNotBlank())
            assertTrue("privacyBadge should not be blank for $lang", strings.privacyBadge.isNotBlank())
            assertTrue("ourManifesto should not be blank for $lang", strings.ourManifesto.isNotBlank())
            assertTrue("openPdfCtrlO should not be blank for $lang", strings.openPdfCtrlO.isNotBlank())
            assertTrue("selectLanguage should not be blank for $lang", strings.selectLanguage.isNotBlank())
            assertTrue("tabAllTools should not be blank for $lang", strings.tabAllTools.isNotBlank())
            assertTrue("tabCompress should not be blank for $lang", strings.tabCompress.isNotBlank())
            assertTrue("tabOrganize should not be blank for $lang", strings.tabOrganize.isNotBlank())
            assertTrue("tabConvert should not be blank for $lang", strings.tabConvert.isNotBlank())
            assertTrue("tabReader should not be blank for $lang", strings.tabReader.isNotBlank())
            assertTrue("tabSecurity should not be blank for $lang", strings.tabSecurity.isNotBlank())
            assertTrue("tabBatch should not be blank for $lang", strings.tabBatch.isNotBlank())
            assertTrue("homeHeroTitle should not be blank for $lang", strings.homeHeroTitle.isNotBlank())
            assertTrue("pageStudioTitle should not be blank for $lang", strings.pageStudioTitle.isNotBlank())
            assertTrue("compressTitle should not be blank for $lang", strings.compressTitle.isNotBlank())
            assertTrue("manifestoDialogTitle should not be blank for $lang", strings.manifestoDialogTitle.isNotBlank())
        }
    }

    @Test
    fun testArabicIsRtl() {
        assertTrue(DesktopLanguage.AR.isRtl)
        assertFalse(DesktopLanguage.EN.isRtl)
        assertFalse(DesktopLanguage.DE.isRtl)
    }

    @Test
    fun testCliOverrideAndLanguageSwitching() {
        // CLI --lang=fr
        val showSetup = DesktopLocalization.initFromCli("fr")
        assertFalse("CLI override should skip setup dialog", showSetup)
        assertEquals(DesktopLanguage.FR, DesktopLocalization.currentLanguage)
        assertEquals("PDFchemy Tools", DesktopLocalization.strings.appTitle)
        assertEquals("File par Lots", DesktopLocalization.strings.tabBatch)

        // Switch to German dynamically
        DesktopLocalization.currentLanguage = DesktopLanguage.DE
        assertEquals(DesktopLanguage.DE, DesktopLocalization.currentLanguage)
        assertEquals("Stapel-Warteschlange", DesktopLocalization.strings.tabBatch)

        // Switch to Romanian dynamically
        DesktopLocalization.currentLanguage = DesktopLanguage.RO
        assertEquals(DesktopLanguage.RO, DesktopLocalization.currentLanguage)
        assertEquals("Procesare în Lot", DesktopLocalization.strings.tabBatch)

        // Force setup flag
        val forceSetup = DesktopLocalization.initFromCli(null, forceSetup = true)
        assertTrue("forceSetup = true should return true", forceSetup)
    }

    @Test
    fun testDefaultLanguagePersistenceAndResolution() {
        // Explicitly set default language to English
        DesktopLocalization.setDefaultLanguage(DesktopLanguage.EN)
        assertEquals(DesktopLanguage.EN, DesktopLocalization.defaultLanguage)
        assertEquals(DesktopLanguage.EN, DesktopLocalization.currentLanguage)
        assertEquals(DesktopLanguage.EN, DesktopLocalization.resolveSavedDefaultLanguage())
        assertFalse(DesktopLocalization.isFirstRun)

        // Set default to German
        DesktopLocalization.setDefaultLanguage(DesktopLanguage.DE)
        assertEquals(DesktopLanguage.DE, DesktopLocalization.defaultLanguage)
        assertEquals(DesktopLanguage.DE, DesktopLocalization.currentLanguage)
        assertEquals(DesktopLanguage.DE, DesktopLocalization.resolveSavedDefaultLanguage())

        // Reset to system language by passing null
        DesktopLocalization.setDefaultLanguage(null)
        assertNull(DesktopLocalization.defaultLanguage)
        assertNull(DesktopLocalization.resolveSavedDefaultLanguage())
        assertEquals(DesktopLanguage.detectSystemLanguage(), DesktopLocalization.currentLanguage)

        // Set default back to English so user environment opens in English
        DesktopLocalization.setDefaultLanguage(DesktopLanguage.EN)
        assertEquals(DesktopLanguage.EN, DesktopLocalization.defaultLanguage)
    }
}
