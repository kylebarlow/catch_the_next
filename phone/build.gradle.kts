import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Private credentials for the Play edition come from local.properties (gitignored). The
// non-crashing fallback keeps the fdroid variant buildable on a machine without the file.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(FileInputStream(f))
}
val playApiKey = localProps.getProperty("APP_API_KEY") ?: "debug_key"
val playBaseUrl = localProps.getProperty("CATCH_THE_NEXT_BASE_URL")
    ?: "http://10.0.2.2:39217/api/v2/rest"

// The Bay (F-Droid) edition ships a *public*, committed API key. The backend hard-scopes it to
// 511 / local Bay Area data only (never Transitland) — it is intentionally public, rate-limited
// server-side, and safe to build reproducibly from source with no secrets. See server/auth.py.
val bayPublicApiKey = "ueNH5S9k1j8R15BtZeuaZZNyReTHTDqv"
val bayBaseUrl = "https://transitapi.nfshost.com/api/v2/rest"

// Play upload signing. Credentials live in a gitignored keystore.properties (never commit the
// keystore). When absent (CI / F-Droid / contributors), release falls back to debug signing so
// the project still builds. Enroll in Play App Signing and upload the AAB built with this key.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(FileInputStream(f))
}
val hasUploadKeystore = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "dev.catchthenext.phone"
    compileSdk = 36

    defaultConfig {
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasUploadKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Use the upload keystore when configured; otherwise debug-sign so the build works
            // without secrets (F-Droid builds its own signature; never ship this to Play).
            signingConfig = if (hasUploadKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            applicationId = "com.kylebarlow.catchthenext"
            buildConfigField("String", "APP_API_KEY", "\"$playApiKey\"")
            buildConfigField("String", "CATCH_THE_NEXT_BASE_URL", "\"$playBaseUrl\"")
        }
        create("fdroid") {
            dimension = "distribution"
            applicationId = "com.kylebarlow.catchthenext.bay"
            buildConfigField("String", "APP_API_KEY", "\"$bayPublicApiKey\"")
            buildConfigField("String", "CATCH_THE_NEXT_BASE_URL", "\"$bayBaseUrl\"")
            // app_name "Catch The Next: Bay" comes from src/fdroid/res (overrides main).
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":shared-android"))
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.compose)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.work.runtime.ktx)
    // FusedLocation is GMS — Play edition only. The fdroid variant uses shared-android's
    // LocationManager-backed twin and the location/sync code in phone/src/play.
    "playImplementation"(libs.play.services.location)
    "playImplementation"(libs.coroutines.play.services)
    // A play-side transitive dep pulls androidx.fragment 1.1.0, which trips the
    // InvalidFragmentVersionForActivityResult lint-vital check on release. Force >= 1.3.0.
    // (The fdroid variant doesn't pull old fragment, so it's scoped to play.)
    "playImplementation"("androidx.fragment:fragment:1.8.5")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
