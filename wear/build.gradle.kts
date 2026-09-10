import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.catchthenext.wear"
    compileSdk = 35

    defaultConfig {
        // Match the Play phone edition so the watch app pairs with it. WearOS requires GMS
        // on-device, so the watch app is Play-only and always uses shared-android's `play` variant.
        applicationId = "com.kylebarlow.catchthenext"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // shared-android is flavored (play/fdroid); this un-flavored module resolves `play`.
        missingDimensionStrategy("distribution", "play")
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

// Load APP_API_KEY from local.properties
val properties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    properties.load(FileInputStream(localPropertiesFile))
}
val apiKey = properties.getProperty("APP_API_KEY") ?: "debug_key"
val baseUrl = properties.getProperty("CATCH_THE_NEXT_BASE_URL") ?: "http://10.0.2.2:39217/api/v2/rest"

android.defaultConfig {
    buildConfigField("String", "APP_API_KEY", "\"$apiKey\"")
    buildConfigField("String", "CATCH_THE_NEXT_BASE_URL", "\"$baseUrl\"")
}

dependencies {
    implementation(project(":shared-android"))
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.compose.material.icons)
    implementation(libs.wear.compose.navigation)
    implementation(libs.horologist.compose.layout)
    implementation(libs.horologist.compose.material)
    implementation(libs.horologist.tiles)
    implementation(libs.horologist.compose.tools)
    implementation(libs.wear.tiles)
    implementation(libs.wear.tiles.material)
    implementation(libs.protolayout)
    implementation(libs.protolayout.material)
    testImplementation(libs.junit.jupiter)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
