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

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation(project(":native-renderer"))
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.android.gms:play-services-tasks:18.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    
    // Parser dependencies belong exclusively to pdfjail/
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("com.vladsch.flexmark:flexmark-all:0.64.8")
    
    // Jackson for text formats
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.8")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-csv:2.18.8")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.8")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.18.8")
}
