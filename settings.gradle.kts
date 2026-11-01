pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }

        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()

        // Required for Sherpa-ONNX
        maven {
            url = uri("https://jitpack.io")
        }
    }
}

rootProject.name = "ArogyaDoot"

include(":app")

// AndroLLM Whisper.cpp native speech-recognition module.
// Expected folder:
// ARFAS/
// ├── app/
// ├── whisper/
// ├── third_party/
// │   └── whisper.cpp/
// └── settings.gradle.kts
include(":whisper")