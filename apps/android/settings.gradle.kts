pluginManagement {
  resolutionStrategy {
    eachPlugin {
      when (requested.id.id) {
        "com.android.application", "com.android.library" ->
          useModule("com.android.tools.build:gradle:" + (requested.version ?: "9.1.1"))
      }
    }
  }
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

rootProject.name = "Shield VPN"

include(":app")
