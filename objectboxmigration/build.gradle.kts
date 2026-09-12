import org.gradle.kotlin.dsl.apply
import plugin.KiwixConfigurationPlugin

plugins {
  id("com.android.library")
}

buildscript {
  repositories {
    google()
    mavenCentral()
    maven { setUrl("https://jitpack.io") }
  }

  dependencies {
    classpath(Libs.objectbox_gradle_plugin)
  }
}

plugins.apply(KiwixConfigurationPlugin::class)
apply(plugin = "io.objectbox")

// ObjectBox's own task isn't configuration-cache compatible yet - objectbox-java#948.
tasks.withType<io.objectbox.gradle.PrepareTask>().configureEach {
  notCompatibleWithConfigurationCache("ObjectBox does not yet support the Gradle Configuration Cache")
}

android {
  namespace = "org.kiwix.kiwixmobile.migration"

  defaultConfig {
    minSdk = Config.minSdk

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    consumerProguardFiles("consumer-rules.pro")
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
}

dependencies {
  implementation(Libs.objectbox_kotlin)
  api(project(":core"))
}

// This module has no unit tests of its own; it only inherits `core`'s shared test
// fixtures (MainDispatcherRule, TestApplication, etc.), none of which declare `@Test`
// methods, so the JUnit Platform has nothing to discover here.
tasks.withType<Test>().configureEach {
  failOnNoDiscoveredTests = false
}
