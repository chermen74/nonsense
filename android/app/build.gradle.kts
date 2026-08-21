plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nonsense"
    compileSdk = 34

    // A fixed debug key, checked in on purpose.
    //
    // Without this, CI signs every build with a keystore Gradle generates on
    // the spot, because a fresh runner has no ~/.android/debug.keystore. Every
    // APK then has a different signer: Android will not install one over the
    // last, and Play Protect meets a brand new unknown app each time. The
    // published APK proved it — its certificate's notBefore was the minute the
    // build ran.
    //
    // It is a debug key with the conventional android/androiddebugkey
    // credentials. It cannot publish to Play, and it is public by design, the
    // same way the keystore shipped in the Android SDK is.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.nonsense"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Gradle prints only "there were failing tests" by default, which is useless
// from CI. Name them, with the assertion message.
tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showStandardStreams = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Toy.kt has no android.* in it, so the simulation is testable on a plain JVM
    testImplementation("junit:junit:4.13.2")
}
