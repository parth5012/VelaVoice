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
        maven { url = uri("https://www.jitpack.io") }
        mavenLocal {
            content {
                includeGroup("com.microsoft.onnxruntime")
            }
        }
    }
}

rootProject.name = "vela-transcription-sdk"

include(":vela-common")
include(":vela-whisper")
include(":vela-cleaner")
include(":vela-voice-ui")
include(":vela-core")
