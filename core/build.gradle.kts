plugins {
    alias(libs.plugins.kotlin.jvm)
    id("java-library")
}

group = "dev.catchthenext"
version = "1.0-SNAPSHOT"

dependencies {
    api(libs.okhttp)
    api(libs.gson)
    api(libs.dotenv)
    api(libs.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
