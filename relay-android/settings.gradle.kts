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
    // Shared vendor-neutral lib (com.ambientlink:core-android). AGP here (8.6) skews
    // from core-android's (8.7), so this is consumed as a published AAR, not a
    // composite build. One-time: run `./gradlew publishToMavenLocal` in
    // ../../ambient-link-core/core-android (or resolve from GitHub Packages in CI).
    mavenLocal()
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

rootProject.name = "AmbientLinkFinal"
include(":app")
