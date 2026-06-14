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
        username = ""
        password = System.getenv("GITHUB_TOKEN") ?: localProperties.getProperty("github_token", "")
      }
    }
  }
}

rootProject.name = "AmbientLinkFinal"
include(":app")
