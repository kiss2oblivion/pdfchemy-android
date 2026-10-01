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
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.android.gms:play-services-tasks:18.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    
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
