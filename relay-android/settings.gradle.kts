import java.util.Properties

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

val localProperties = Properties().apply {
  val f = rootDir.resolve("local.properties")
  if (f.exists()) f.inputStream().use(::load)
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    // Meta DAT SDK — private GitHub Packages registry. Set github_token in local.properties.
    maven {
      url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
      credentials {
        username = System.getenv("GITHUB_ACTOR")
          ?: localProperties.getProperty("github_user")
          ?: "maceip"
        password = System.getenv("GITHUB_TOKEN") ?: localProperties.getProperty("github_token", "")
      }
    }
  }
}

// Shared vendor-neutral lib (com.ambientlink:core-android), consumed as a Gradle
// composite build — same mechanism as ambient-link-google. Aligned on AGP 8.7 /
// Kotlin 2.1.20 / Gradle 8.14.1 so no published artifact / publish step is needed.
// Requires ambient-link-core checked out as a sibling of ambient-link-meta.
includeBuild("../../ambient-link-core/core-android")

rootProject.name = "AmbientLinkFinal"
include(":app")
