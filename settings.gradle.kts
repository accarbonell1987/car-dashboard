pluginManagement {
    repositories {
        // El repo de Google solo para paquetes de Android; el resto sale de Maven Central y del portal.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("com\\.google\\.testing.*")
                includeGroupByRegex("com\\.google\\.prefab.*")
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
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("com\\.google\\.testing.*")
                includeGroupByRegex("com\\.google\\.prefab.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "carobd"

include(":core")
include(":server")

// Las apps Android necesitan el SDK de Android y el repositorio de Google.
// Para compilar solo core y server (por ejemplo sin SDK): gradle build -Pcarobd.android=false
val withAndroid = providers.gradleProperty("carobd.android").map { it.toBoolean() }.getOrElse(true)
if (withAndroid) {
    include(":screen")
    include(":mobile")
}
