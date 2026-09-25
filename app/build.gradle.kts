plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.pdfchemy.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pdfchemy.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 13
        versionName = "2.0.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            val keystoreProperties = java.util.Properties()
            if (keystorePropertiesFile.exists()) {
                keystoreProperties.load(java.io.FileInputStream(keystorePropertiesFile))
            }

            storeFile = file("release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: keystoreProperties["KEYSTORE_PASSWORD"]?.toString() ?: throw GradleException("Missing KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS") ?: keystoreProperties["KEY_ALIAS"]?.toString() ?: throw GradleException("Missing KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD") ?: keystoreProperties["KEY_PASSWORD"]?.toString() ?: throw GradleException("Missing KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "AD_UNIT_ID", "\"ca-app-pub-3940256099942544/1033173712\"") // Google Test Ad Unit ID
            buildConfigField("String", "BANNER_AD_UNIT_ID", "\"ca-app-pub-3940256099942544/6300978111\"") // Google Test Banner ID
            manifestPlaceholders["adMobAppId"] = "ca-app-pub-3940256099942544~3347511713" // Google Test App ID
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "AD_UNIT_ID", "\"ca-app-pub-8945763551071628/9314012106\"")
            buildConfigField("String", "BANNER_AD_UNIT_ID", "\"ca-app-pub-8945763551071628/5697788038\"")
            manifestPlaceholders["adMobAppId"] = "ca-app-pub-8945763551071628~8206577473"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
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
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE-W3C-TEST"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

configurations.all {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    exclude(group = "org.bouncycastle", module = "bcpkix-jdk15to18")
    exclude(group = "org.bouncycastle", module = "bcutil-jdk15to18")
}

dependencies {
    implementation("androidx.core:core-splashscreen:1.0.1")
    // 1. Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.02.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Image Loading
    implementation("io.coil-kt:coil-compose:2.6.0")

    // 2. Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // 3. PdfBox-Android
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // 4. AdMob
    implementation("com.google.android.gms:play-services-ads:23.2.0")

    // Google User Messaging Platform (UMP) for GDPR
    implementation("com.google.android.ump:user-messaging-platform:2.2.0")

    // ML Kit Document Scanner
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    // ML Kit Text Recognition
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // 5. Google Play Billing Library
    implementation("com.android.billingclient:billing-ktx:8.0.0")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.window:window:1.2.0")

    // Text Conversion Libraries
    implementation("org.jsoup:jsoup:1.23.1")
    implementation("com.vladsch.flexmark:flexmark-all:0.64.8")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.8")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-csv:2.18.8")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.8")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.18.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // PKI Digital Signatures (BouncyCastle)
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
}
