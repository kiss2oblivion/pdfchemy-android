// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.google.gms:google-services:4.3.15")
    }
}

// These checks resolve dependencies but do not compile hostile-document engines.
tasks.register("securityArchitecture") {
    doLast {
        val allowed = mapOf(
            ":app-host" to mapOf("implementation" to setOf(":pdf-ipc"), "runtimeOnly" to setOf(":pdf-jail", ":pdf-renderer")),
            ":pdf-jail" to mapOf("implementation" to setOf(":pdf-ipc", ":tesseract-wrapper")),
            ":pdf-renderer" to mapOf("implementation" to setOf(":pdf-ipc")),
            ":pdf-ipc" to emptyMap()
        )
        allowed.forEach { (path, permitted) ->
            val module = project(path)
            listOf("api", "implementation", "compileOnly", "runtimeOnly").forEach { name ->
                module.configurations.findByName(name)?.dependencies?.withType(org.gradle.api.artifacts.ProjectDependency::class.java)?.forEach { dependency ->
                    check(dependency.path in permitted[name].orEmpty()) { "Forbidden module edge: $path $name ${dependency.path}" }
                }
            }
        }
        val host = project(":app-host")
        val forbiddenGroups = setOf("com.tom-roush", "org.apache.pdfbox", "org.bouncycastle", "org.jsoup", "com.vladsch.flexmark", "io.coil-kt")
        listOf("debugCompileClasspath", "releaseCompileClasspath").forEach { name ->
            host.configurations.getByName(name).incoming.resolutionResult.allComponents.forEach { component ->
                val id = component.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier
                check(id?.group !in forbiddenGroups) { "Host parser dependency: $id" }
            }
        }
        val prohibited = listOf("android.graphics.ImageDecoder", "MediaStore.Images.Media.getBitmap", "java.util.zip.ZipFile", "java.util.zip.ZipInputStream", "coil.", "android.graphics.pdf.PdfDocument")
        host.file("src/main").walkTopDown().filter { it.extension in setOf("kt", "java") }.forEach { source ->
            check(prohibited.none { source.readText().contains(it) }) { "Host decoder/generator path: $source" }
        }
    }
}

tasks.register("dependencyInventory") {
    doLast {
        val coordinates = sortedSetOf<String>()
        listOf(":app-host", ":pdf-ipc", ":pdf-jail", ":pdf-renderer").forEach { path ->
            project(path).configurations.getByName("releaseRuntimeClasspath").incoming.resolutionResult.allComponents.forEach { component ->
                val id = component.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier
                if (id != null) coordinates.add("${id.group}:${id.module}:${id.version}")
            }
        }
        val output = file("build/security/dependencies.txt")
        output.parentFile.mkdirs()
        output.writeText(coordinates.joinToString("\n", postfix = "\n"))
        println("Resolved ${coordinates.size} production dependencies for OSV scanning")
    }
}
