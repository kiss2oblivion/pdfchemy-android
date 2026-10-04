import java.security.MessageDigest

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pdfchemy.pdfjail"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        buildConfig = true
        aidl = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE-LGPL-3.txt"
            excludes += "/META-INF/LICENSE-LGPL-2.1.txt"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE-notice.md"
            excludes += "/META-INF/NOTICE.md"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE-W3C-TEST"
        }
    }
}

configurations.configureEach {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    exclude(group = "org.bouncycastle", module = "bcpkix-jdk15to18")
    exclude(group = "org.bouncycastle", module = "bcutil-jdk15to18")
}

dependencies {
    implementation("com.google.guava:guava:33.7.2-android")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation(project(":pdf-ipc"))
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation(files("libs/tesseract4android-4.9.0-inmemory.aar"))
    implementation("androidx.annotation:annotation:1.9.1")
    
    // Parser dependencies belong exclusively to pdfjail/
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
    implementation("com.vladsch.flexmark:flexmark-all:0.64.8")
    implementation("org.jsoup:jsoup:1.23.2") { version { strictly("1.23.2") } } // Upstream #2556 fix; regression test guards resolved artifact.
    
    // Jackson for text formats
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.11")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-csv:2.18.11")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.11")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.18.11")
}

val pinnedTesseractSha256 = "d8e6197e73ee8cb7f98079d1ae85e6b6dbff826f2011af84e2b493549bcf62fa"

val verifyTesseractAarHash = tasks.register("verifyTesseractAarHash") {
    description = "Enforces executable policy: verifies the vendored Tesseract4Android AAR SHA-256 against pinned cryptographic hash."
    val aarFile = file("libs/tesseract4android-4.9.0-inmemory.aar")
    inputs.file(aarFile)
    outputs.upToDateWhen { true }
    doLast {
        if (!aarFile.exists()) {
            throw GradleException("Vendored Tesseract4Android AAR is missing at ${aarFile.absolutePath}")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = aarFile.readBytes()
        val hashBytes = digest.digest(bytes)
        val sb = StringBuilder()
        for (b in hashBytes) {
            sb.append(String.format("%02x", b))
        }
        val actualHash = sb.toString()
        if (!actualHash.equals(pinnedTesseractSha256, ignoreCase = true)) {
            throw GradleException(
                "Vendored Tesseract4Android AAR SHA-256 mismatch!\n" +
                "Expected: $pinnedTesseractSha256\n" +
                "Actual:   $actualHash\n" +
                "Build aborted due to untrusted/modified vendored artifact."
            )
        }
        logger.lifecycle("verifyTesseractAarHash: Verified tesseract4android-4.9.0-inmemory.aar SHA-256 ($actualHash) matches pinned policy.")
    }
}

tasks.matching { it.name.startsWith("compile") || it.name.startsWith("preBuild") }.configureEach {
    dependsOn(verifyTesseractAarHash)
}
