import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.catchthenext.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.catchthenext"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.compose)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
