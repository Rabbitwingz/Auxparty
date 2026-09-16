plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
}

// CI supplies these; a local or unsigned build falls back to the debug key.
val signingKeystore: String? = System.getenv("SIGNING_KEYSTORE_PATH")
val buildNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// Where the app connects, and the site people pair a browser on. Not secrets.
val relayUrl: String = providers.gradleProperty("relayUrl").get()
val webUrl: String = providers.gradleProperty("webUrl").get()

android {
    // Namespace and applicationId predate the Auxparty name. The applicationId must
    // never change: Android would treat the app as new and installs would stop updating.
    namespace = "app.musicremote"
    // Material 3 1.5 alphas require compiling against API 37. targetSdk (runtime behaviour) stays 36.
    compileSdk = 37

    defaultConfig {
        applicationId = "app.musicremote"
        minSdk = 26
        targetSdk = 36
        // Every CI run must increase versionCode or Android refuses the update.
        versionCode = buildNumber
        versionName = "0.3.$buildNumber"

        buildConfigField("String", "RELAY_URL", "\"$relayUrl\"")
        buildConfigField("String", "WEB_URL", "\"$webUrl\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
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
            // Compose is large; R8 keeps the APK size reasonable.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            // Robolectric screenshot tests need real resources (fonts, drawables).
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    // Material 3 Expressive APIs exist only in the 1.5 alphas; the BOM pins stable 1.4.0.
    implementation("androidx.compose.material3:material3:1.5.0-alpha28")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.graphics:graphics-shapes:1.1.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    // Material Color Utilities port: seed colour from artwork, dynamic schemes.
    implementation("com.materialkolor:material-kolor:5.0.1")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub on the JVM; tests need the real one.
    testImplementation("org.json:json:20240303")

    // Screenshot tests: Compose rendered on the JVM by Robolectric, captured by Roborazzi.
    testImplementation(composeBom)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.74.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.74.0")
}
