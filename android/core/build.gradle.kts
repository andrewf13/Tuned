plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

/**
 * Shared by the TV app and the phone/tablet app: provider logins (Xtream, M3U), profiles,
 * the TV guide and sports recogniser, backups, and the player engine.
 */
android {
    namespace = "com.andrewf13.tuned.core"
    compileSdk = 35
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    val media3 = "1.5.1"
    api("androidx.media3:media3-exoplayer:$media3")
    api("androidx.media3:media3-exoplayer-hls:$media3")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    api(platform("androidx.compose:compose-bom:2025.01.00"))
    api("androidx.compose.runtime:runtime") // PlayerController exposes Compose state

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
