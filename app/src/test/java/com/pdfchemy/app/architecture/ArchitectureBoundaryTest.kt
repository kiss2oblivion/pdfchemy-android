package com.pdfchemy.app.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.Test

class ArchitectureBoundaryTest {

    @Test
    fun host process must not use PDDocument or PdfRenderer() {
        val importedClasses: JavaClasses = ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.pdfchemy.app")

        val rule = noClasses()
            .that().resideOutsideOfPackages("..jail.engines..")
            .and().haveNameNotMatching(".*NativeRendererCoordinator.*")
            .and().haveNameNotMatching(".*Test.*")
            .should().dependOnClassesThat().haveFullyQualifiedName("com.tom_roush.pdfbox.pdmodel.PDDocument")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("android.graphics.pdf.PdfRenderer")
            .because("PDF parsing and native rendering must be isolated to the jail/engines package or NativeRendererCoordinator. The Gateway and host process MUST NEVER parse attacker-controlled document syntax.")

        rule.check(importedClasses)
    }
}
