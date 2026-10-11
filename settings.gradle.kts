// Without this, plugin resolution (e.g. buildSrc's `kotlin-dsl` plugin, and its
// transitive kotlin-stdlib/kotlin-gradle-plugin deps) only ever looks at
// gradlePluginPortal() - buildSrc/build.gradle.kts's own mavenCentral() has no
// effect on it - so a momentary gap there fails the build with nowhere to fall back.
pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    google()
  }
}

include(
  ":core",
  ":app",
  ":branded",
  ":install_time_asset",
  ":objectboxmigration",
  ":defaultmigration",
  ":benchmark"
)
rootProject.name = "kiwix-android"
