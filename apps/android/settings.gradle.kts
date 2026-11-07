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
    maven("https://maven.aliyun.com/repository/google")
    maven("https://edgedl.me.gvt1.com/dl/android/maven2") {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
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

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    maven("https://maven.aliyun.com/repository/google")
    maven("https://edgedl.me.gvt1.com/dl/android/maven2")
    google()
    mavenCentral()
  }
}

rootProject.name = "Shield VPN"

include(":app")
