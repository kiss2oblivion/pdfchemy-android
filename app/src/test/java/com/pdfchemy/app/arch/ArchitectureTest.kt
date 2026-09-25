package com.pdfchemy.app.arch

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.Test

class ArchitectureTest {

    @Test
    fun `no host components should use PDDocument directly`() {
        val importedClasses = ClassFileImporter().importPackages("com.pdfchemy.app")

        val rule = noClasses()
            .that().resideOutsideOfPackages(
                "..jail..", 
                "..sandbox..",
                "..arch.." // Allow tests to reference it if needed
            )
            .should().dependOnClassesThat().haveFullyQualifiedName("com.tom_roush.pdfbox.pdmodel.PDDocument")
            .because("PDDocument must only be used within isolated jails to prevent parser exploits in the host process")

        rule.check(importedClasses)
    }

    @Test
    fun `no host components should use PdfRenderer directly`() {
        val importedClasses = ClassFileImporter().importPackages("com.pdfchemy.app")

        val rule = noClasses()
            .that().resideOutsideOfPackages(
                "..jail..",
                "..sandbox..",
                "..arch.."
            )
            .should().dependOnClassesThat().haveFullyQualifiedName("android.graphics.pdf.PdfRenderer")
            .because("PdfRenderer must only be used within isolated jails to prevent memory corruption exploits in the host process")

        rule.check(importedClasses)
    }
}
