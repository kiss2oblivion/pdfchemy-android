package com.pdfchemy.app.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class HostBytecodeSecurityTest {
    @Test fun everyHostProductionClassIsFreeOfDocumentDecoders() {
        val paths = requireNotNull(System.getProperty("hostProductionClasses")).split(File.pathSeparator)
            .map(::File).filter(File::exists).map(File::toPath)
        val classes = ClassFileImporter().importPaths(paths)
        assertFalse("Host bytecode must actually be imported", classes.isEmpty())
        listOf("com.tom_roush.pdfbox.pdmodel.PDDocument", "android.graphics.pdf.PdfRenderer",
            "android.graphics.BitmapFactory", "android.graphics.ImageDecoder", "java.util.zip.ZipFile",
            "java.util.zip.ZipInputStream").forEach { forbidden ->
            noClasses().should().dependOnClassesThat().haveFullyQualifiedName(forbidden).check(classes)
        }
    }
}
