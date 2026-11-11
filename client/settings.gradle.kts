pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Kotlin/Wasm adds the official Node/Yarn distribution repositories to setup tasks.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "decentralert-client"
include(":shared", ":dashboard")

// Android configuration is opt-in so shared/web builds work on machines without an SDK.
if (providers.gradleProperty("enableAndroid").orNull == "true") {
    include(":mobile")
}

