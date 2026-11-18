pluginManagement {
  repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral()
    google()
  }
}

rootProject.name = "Overhand"

include(":app")

// #1009: the Preview's real-WebView check (runs/native/preview_avd). A plain-Android test module that compiles the
// shipped preview classes from app/src/main/java; included only with -PpreviewAvd, so other builds never see it.
if (providers.gradleProperty("previewAvd").isPresent) {
  include(":previewavd"); project(":previewavd").projectDir = file("../../runs/native/preview_avd/module")
}
