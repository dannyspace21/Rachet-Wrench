// app/build.gradle.kts — how the Android app is built and which libraries
// it uses.  Every version is pinned exactly; the full resolved list
// (including indirect libraries) is locked in app/gradle.lockfile.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.ratchetwrench.app"
    compileSdk = 37
    compileSdkMinor = 2  // Android 17 QPR2 (37.2) — needed by the latest Compose libraries

    defaultConfig {
        applicationId = "com.ratchetwrench.app"
        minSdk = 29          // Android 10+: scoped storage, modern TLS, photo picker backport
        targetSdk = 37
        // Every published release must have a HIGHER versionCode than the one
        // before, or phones won't install it as an update.  The GitHub release
        // workflow passes its run number in RW_VERSION_CODE; local builds use 1.
        versionCode = (System.getenv("RW_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("RW_VERSION_NAME") ?: "1.0.0-local"
    }

    // PERMANENT RELEASE SIGNING KEY
    // Android only installs an update if it is signed with the SAME key as
    // the installed app.  The key itself is never stored in this repository:
    // the GitHub workflow restores it from encrypted repository secrets into
    // a temporary file and passes its location and passwords in these
    // environment variables.  (Create the key with
    // scripts/create-android-signing-key.sh — see docs/ANDROID-UPDATES.md.)
    val releaseKeystore = System.getenv("RW_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.isFile }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("RW_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RW_KEY_ALIAS")
                keyPassword = System.getenv("RW_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Shrink and obfuscate release builds (makes reverse-engineering
            // harder and removes unused code).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Use the permanent key when available.  Without it (e.g. a local
            // build on your own computer) fall back to the debug key so the
            // APK still installs; the GitHub release workflow REFUSES to
            // publish anything not signed with the permanent key.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.kotlin_module")
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin {
    jvmToolchain(21)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Record exact versions of every library (direct and indirect).
dependencyLocking {
    lockAllConfigurations()
}

// SECURITY: some libraries (and Android's own build tools such as lint) pull
// in older versions of these packages that have known vulnerabilities
// (found by OSV-Scanner).  Force the patched versions everywhere.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        when {
            requested.group == "com.google.guava" && requested.name == "guava" -> {
                val flavor = if (requested.version?.endsWith("-jre") == true) "jre" else "android"
                useVersion("33.8.0-$flavor"); because("GHSA-xxph-c9ww-hj94")
            }
            requested.group == "org.apache.commons" && requested.name == "commons-lang3" -> { useVersion("3.22.0"); because("GHSA-j288-q9x7-2f5v") }
            requested.group == "org.apache.httpcomponents" && requested.name == "httpclient" -> { useVersion("4.5.14"); because("GHSA-7r82-7xv7-xcpj") }
            requested.group == "org.bouncycastle" && requested.name.endsWith("-jdk18on") -> { useVersion("1.87"); because("GHSA-9pwp-9qqc-pr26, GHSA-qp49-qgx5-5m26, GHSA-wg6q-6289-32hp") }
        }
    }
}

// Note: ML Kit includes Google's "datatransport" usage logger.  Its upload
// backend is switched off in AndroidManifest.xml (see the comment there), so
// no usage data ever leaves the phone.

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.11.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.1")

    // Offline database
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // Background sync + reminder checks
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    // Camera preview for VIN barcode and door-sticker scanning
    implementation("androidx.camera:camera-camera2:1.7.0")
    implementation("androidx.camera:camera-lifecycle:1.7.0")
    implementation("androidx.camera:camera-view:1.7.0")

    // On-device ML Kit (bundled models: work offline, nothing uploaded)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Networking + JSON
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
}
