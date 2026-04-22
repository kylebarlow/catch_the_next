pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "catch_the_next"
include(":core")
include(":cli")
include(":wear")

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
