plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nonsense"
    compileSdk = 34

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
