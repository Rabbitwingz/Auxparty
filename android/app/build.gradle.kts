import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI supplies these; a local or unsigned build falls back to the debug key.
val signingKeystore: String? = System.getenv("SIGNING_KEYSTORE_PATH")
val buildNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// Where the app connects, and the site people pair a browser on. Not secrets.
val relayUrl: String = providers.gradleProperty("relayUrl").get()
val webUrl: String = providers.gradleProperty("webUrl").get()

android {
    namespace = "app.musicremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.musicremote"
        minSdk = 26
        targetSdk = 35
        // Every CI run must increase versionCode or Android refuses the update.
        versionCode = buildNumber
        versionName = "0.2.$buildNumber"

        buildConfigField("String", "RELAY_URL", "\"$relayUrl\"")
        buildConfigField("String", "WEB_URL", "\"$webUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (signingKeystore != null) {
            create("release") {
                storeFile = file(signingKeystore)
                storeType = "pkcs12"
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Diagnostics-stage app; don't let advisory lint findings fail CI builds.
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub on the JVM; tests need the real one.
    testImplementation("org.json:json:20240303")
}
