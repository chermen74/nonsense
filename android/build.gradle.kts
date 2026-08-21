// Play Billing 9 wants compileSdk 36, which AGP 8.5 predates entirely — it
// refuses at checkDebugAarMetadata rather than at compile. compileSdk had to
// move for the Play release anyway: Play requires new apps to target a
// recent API level, and 34 is two years behind.
plugins {
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
}
