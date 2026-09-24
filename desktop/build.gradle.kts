import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Compose Multiplatform Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // Pruned Material Icons (Only the 30 icons used in the app, saving ~35 MB compressed / 84 MB uncompressed)
    implementation(files("libs/material-icons-pruned.jar"))
    implementation(compose.components.resources)

    // Apache PDFBox for pure JVM Desktop (Windows & Linux)
    implementation("org.apache.pdfbox:pdfbox:2.0.31")

    // BouncyCastle for PKI (Digital Signatures)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78")

    // Tess4J for Desktop OCR
    implementation("net.sourceforge.tess4j:tess4j:5.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")
    
    // JSON Parsing for Update Manifests
    implementation("com.google.code.gson:gson:2.14.0")

    testImplementation("junit:junit:4.13.2")
}

val buildArkhamLaunchers by tasks.registering {
    doLast {
        val isWin = org.gradle.internal.os.OperatingSystem.current().isWindows
        val cppDir = file("src/main/cpp")
        
        try {
            val configure = ProcessBuilder("cmake", "-S", ".", "-B", "build").directory(cppDir).inheritIO().start()
            if (configure.waitFor() != 0) throw GradleException("CMake configure failed")
            val build = ProcessBuilder("cmake", "--build", "build", "--config", "Release").directory(cppDir).inheritIO().start()
            if (build.waitFor() != 0) throw GradleException("Arkham launcher build failed")
        } catch (e: java.io.IOException) {
            println("Skipping Arkham launcher build: CMake not found or failed to start (${e.message})")
        }
        
        val outDir = file("src/main/resources/jail")
        outDir.mkdirs()
        
        if (isWin) {
            val exe = file("src/main/cpp/build/Release/arkham-launcher.exe")
            if (!exe.exists()) {
                val debugExe = file("src/main/cpp/build/Debug/arkham-launcher.exe")
                if (debugExe.exists()) debugExe.copyTo(file("src/main/resources/jail/arkham-launcher.exe"), overwrite = true)
            } else {
                exe.copyTo(file("src/main/resources/jail/arkham-launcher.exe"), overwrite = true)
            }
        } else {
            val exe = file("src/main/cpp/build/arkham-launcher-linux")
            if (exe.exists()) exe.copyTo(file("src/main/resources/jail/arkham-launcher-linux"), overwrite = true)
        }
    }
}

tasks.named("processResources") {
    dependsOn(buildArkhamLaunchers)
}

compose.desktop {
    application {
        mainClass = "com.pdfchemy.desktop.MainKt"
        
        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard-rules.pro"))
            obfuscate.set(true)
            optimize.set(true)
        }

        nativeDistributions {
            targetFormats(
                TargetFormat.Msi,
                TargetFormat.Exe,
                TargetFormat.Deb,
                TargetFormat.Rpm
            )
            packageName = "PDFchemy"
            packageVersion = "1.0.9"
            description = "PDFchemy Tools - Local-First Offline PDF Utility"
            copyright = "© 2026 Andrei Ioan Cucos. All rights reserved."
            vendor = "PDFchemy"

            windows {
                menuGroup = "PDFchemy"
                upgradeUuid = "8513DA13-D4B7-31E1-9C15-4ECBE0CAA4D7"
                shortcut = true
                iconFile.set(project.file("src/main/resources/icons/windows/icon.ico"))
            }

            linux {
                shortcut = true
                packageName = "pdfchemy"
                appCategory = "Office;Utility;"
                menuGroup = "Office"
                iconFile.set(project.file("src/main/resources/icons/linux/icon.png"))
            }
        }
    }
}
