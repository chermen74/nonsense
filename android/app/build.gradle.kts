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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Toy.kt has no android.* in it, so the simulation is testable on a plain JVM
    testImplementation("junit:junit:4.13.2")
}
