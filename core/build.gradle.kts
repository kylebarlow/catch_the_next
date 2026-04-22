plugins {
    kotlin("jvm")
    id("java-library")
}

group = "dev.catchthenext"
version = "1.0-SNAPSHOT"

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.google.code.gson:gson:2.10.1")
    api("io.github.cdimascio:dotenv-kotlin:6.4.1")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
