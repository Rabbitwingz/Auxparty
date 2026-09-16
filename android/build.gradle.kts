buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9.4 bundles Kotlin 2.2.10, which cannot read libraries compiled with
        // Kotlin 2.4 (MaterialKolor). This is AGP's documented way to use a newer Kotlin.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.4.0" apply false
    // Must match the Kotlin version above.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("io.github.takahirom.roborazzi") version "1.74.0" apply false
}
