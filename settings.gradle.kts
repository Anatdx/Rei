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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Rei"
include(":app")

// Murasaki API SDK (Kernel-level Binder/Shizuku compatibility layer)
include(":murasaki-api:api")
include(":murasaki-api:aidl")
include(":murasaki-api:provider")
include(":murasaki-api:shared")
include(":murasaki-api:demo-hidden-api-stub")
