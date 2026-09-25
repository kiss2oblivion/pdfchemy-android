package com.pdfchemy.desktop.architecture

import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ArchitectureBoundaryTest {

    @Test
    fun `ensure host boundary does not import hostile parser packages`() {
        val srcDir = File("src/main/kotlin")
        if (!srcDir.exists()) {
            println("Skipping ArchitectureBoundaryTest, srcDir not found.")
            return
        }

        val forbiddenImports = listOf(
            "import org.apache.pdfbox",
            "import net.sourceforge.tess4j",
            "org.apache.pdfbox.",
            "net.sourceforge.tess4j."
        )

        val violations = mutableListOf<String>()

        srcDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val content = file.readText()
            
            // Allow anything in the jail package
            if (content.contains("package com.pdfchemy.desktop.jail")) {
                return@forEach
            }

            for (forbidden in forbiddenImports) {
                if (content.contains("import $forbidden")) {
                    violations.add("File ${file.name} contains forbidden import: $forbidden")
                }
            }

            // Also explicitly check for the PDDocument.load bypass string
            if (content.contains("PDDocument.load")) {
                violations.add("File ${file.name} contains forbidden literal 'PDDocument.load'")
            }
        }

        if (violations.isNotEmpty()) {
            fail("Architecture Boundary Violations Detected:\n" + violations.joinToString("\n"))
        }
    }
}
