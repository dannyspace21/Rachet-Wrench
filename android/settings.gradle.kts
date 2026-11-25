// settings.gradle.kts — tells Gradle where to download build plugins and
// libraries from.  ONLY the two official sources are allowed:
//   google()       = Google's Maven repository (Android, AndroidX, ML Kit)
//   mavenCentral() = Maven Central (Kotlin, OkHttp)
// Each is limited to the groups that actually live there, so a look-alike
// package on the "wrong" repository can't be substituted.
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
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}
rootProject.name = "RatchetWrench"
include(":app")
