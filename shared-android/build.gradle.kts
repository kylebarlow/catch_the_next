plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.catchthenext.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
    }

    // Dual-distribution split: `play` (GMS — FusedLocation + Wearable sync) and `fdroid`
    // (AOSP/FOSS — LocationManager, no sync). See src/play and src/fdroid source sets.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") { dimension = "distribution" }
        create("fdroid") { dimension = "distribution" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.savedstate)
    implementation(libs.datastore.preferences)
    // GMS / WorkManager are confined to the play variant (src/play). The wear module
    // consumes the wearable types transitively, so play-services-wearable must be `api`.
    "playImplementation"(libs.play.services.location)
    "playImplementation"(libs.coroutines.play.services)
    "playImplementation"(libs.work.runtime.ktx)
    "playApi"(libs.play.services.wearable)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.junit.jupiter)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
