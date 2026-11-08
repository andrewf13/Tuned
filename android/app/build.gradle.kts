plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.andrewf13.tuned"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.andrewf13.tuned"
        minSdk = 23
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0"
    }

    // Release signing key is NOT in this public repo (anyone holding it could ship an "update"
    // that installed copies accept). Point TUNED_KEYSTORE at it, with TUNED_KEYSTORE_PASSWORD.
    // Without it, builds are signed with the local debug key and can't update an official install.
    val keystorePath = System.getenv("TUNED_KEYSTORE")
    signingConfigs {
        if (keystorePath != null && file(keystorePath).exists()) {
            create("tuned") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("TUNED_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TUNED_KEY_ALIAS") ?: "tuned"
                keyPassword = System.getenv("TUNED_KEY_PASSWORD") ?: System.getenv("TUNED_KEYSTORE_PASSWORD")
            }
        }
    }
    val tunedSigning = signingConfigs.findByName("tuned") ?: signingConfigs.getByName("debug")

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = tunedSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.tv:tv-material:1.0.0")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
