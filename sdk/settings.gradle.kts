pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()
        maven { url = uri("https://www.jitpack.io") }
    }
}

rootProject.name = "vela-transcription-sdk"

include(":vela-whisper")
include(":vela-cleaner")
include(":vela-voice-ui")
include(":vela-core")
