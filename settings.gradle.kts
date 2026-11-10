pluginManagement {
    includeBuild("build-logic")
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

rootProject.name = "HiVPN"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")

include(":core:common")
include(":core:designsystem")
include(":core:database")
include(":core:datastore")
include(":core:security")
include(":core:network")

include(":domain")
include(":data")

include(":vpn:api")
include(":vpn:wireguard")
include(":vpn:service")

include(":feature:onboarding")
include(":feature:dashboard")
include(":feature:servers")
include(":feature:settings")
include(":feature:profiles")
include(":feature:diagnostics")
include(":feature:statistics")
