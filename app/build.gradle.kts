import com.slack.keeper.optInToKeeper
import plugin.KiwixConfigurationPlugin
import plugin.RenameTarakFileTask
import plugin.TrackedFileRestoreRegistrar

plugins {
  android
  id("com.github.triplet.play") version Versions.com_github_triplet_play_gradle_plugin
}
if (hasProperty("testingMinimizedBuild")) {
  apply(plugin = "io.github.usefulness.keeper")
}
plugins.apply(KiwixConfigurationPlugin::class)

apply(from = rootProject.file("jacoco.gradle"))

fun generateVersionName() = "${Config.versionMajor}.${Config.versionMinor}.${Config.versionPatch}"

val apkPrefix get() = System.getenv("TAG") ?: "kiwix"
val autoModifiedTrackedFiles = listOf(
  File("$rootDir/core/src/main/res/values-b+be+tarask/strings.xml"),
  File("$rootDir/core/src/main/res/values-b+be+tarask+old/strings.xml"),
  File("$rootDir/objectboxmigration/objectbox-models/default.json")
)
val trackedFileBackupDir = File("$rootDir/build/tracked-file-backups")

android {
  // Added namespace in response to Gradle 8.0 and above.
  // This is now specified in the Gradle configuration instead of declaring
  // it directly in the AndroidManifest file.
  namespace = "org.kiwix.kiwixmobile"
  defaultConfig {
    resValue("string", "app_name", "Kiwix")
    resValue("string", "app_search_string", "Search Kiwix")
    versionCode = "".getVersionCode()
    versionName = generateVersionName()
    manifestPlaceholders["permission"] = "android.permission.MANAGE_EXTERNAL_STORAGE"
    testInstrumentationRunner = "org.kiwix.kiwixmobile.testutils.HiltTestRunner"
  }
  lint {
    checkDependencies = true
  }

  buildTypes {
    getByName("debug") {
      multiDexKeepProguard = file("multidex-instrumentation-config.pro")
      buildConfigField("boolean", "KIWIX_ERROR_ACTIVITY", "false")
      buildConfigField("boolean", "IS_PLAYSTORE", "false")
    }

    getByName("release") {
      buildConfigField("boolean", "KIWIX_ERROR_ACTIVITY", "true")
      buildConfigField("boolean", "IS_PLAYSTORE", "false")
      if (properties.containsKey("disableSigning")) {
        signingConfig = null
      }
    }
    create("playStore") {
      manifestPlaceholders += mapOf()
      initWith(getByName("release"))
      matchingFallbacks += "release"
      buildConfigField("boolean", "IS_PLAYSTORE", "true")
      manifestPlaceholders["permission"] = "android.permission.placeholder"
    }
    create("standalone") {
      initWith(getByName("release"))
      matchingFallbacks += "release"
      signingConfig = signingConfigs.getByName("releaseSigningConfig")
      applicationIdSuffix = ".standalone" // Bug Fix #3933
    }
    create("nightly") {
      initWith(getByName("debug"))
      matchingFallbacks += "debug"
      // Build the nightly APK with the released keyStore to make the APK updatable. See #3838
      signingConfig = signingConfigs.getByName("releaseSigningConfig")
      applicationIdSuffix = ".standalone" // Bug Fix #3933
    }
  }
  bundle {
    language {
      // This is disabled so that the App Bundle does NOT split the APK for each language.
      // We're gonna use the same APK for all languages.
      enableSplit = false
    }
  }
  // By default, Android generates dependency metadata (a file containing information
  // about all the dependencies used in the project) and includes it in both APKs and app bundles.
  // This metadata is particularly useful for the Google Play Store, as it provides actionable
  // feedback on potential issues with project dependencies. However, other platforms cannot
  // utilize this metadata. For example, platforms like IzzyOnDroid, GitHub, and our website do not
  // require or utilize the metadata.
  // Since we only upload app bundles to the Play Store for kiwix app, dependency metadata
  // is enabled for app bundles to leverage Google Play Store's analysis
  // and feedback. For APKs distributed outside the Play Store, we exclude this metadata
  // as they do not require this.
  // See https://github.com/kiwix/kiwix-android/issues/4053#issuecomment-2456951610 for details.
  dependenciesInfo {
    // Disables dependency metadata when building APKs.
    // This is for the signed APKs posted on IzzyOnDroid, GitHub, and our website,
    // where dependency metadata is not required or utilized.
    includeInApk = false
    // Enables dependency metadata when building Android App Bundles.
    // This is specifically for the Google Play Store, where dependency metadata
    // is analyzed to provide actionable feedback on potential issues with dependencies.
    includeInBundle = true
  }
}

play {
  enabled.set(true)
  serviceAccountCredentials.set(file("../playstore.json"))
  track.set("internal")
  releaseStatus.set(com.github.triplet.gradle.androidpublisher.ReleaseStatus.COMPLETED)
  resolutionStrategy.set(com.github.triplet.gradle.androidpublisher.ResolutionStrategy.FAIL)
}

androidComponents {
  beforeVariants { variantBuilder ->
    if (variantBuilder.name == "debug" && hasProperty("testingMinimizedBuild")) {
      variantBuilder.optInToKeeper()
    }
  }
}

dependencies {
  androidTestImplementation(Libs.leakcanary_android_instrumentation)
  androidTestImplementation(Libs.shark_android)
  // inject migration module in test cases.
  androidTestImplementation(project(":objectboxmigration"))
  // Inject the migration module for the debug variant, as it is used by the test module.
  debugImplementation(project(":objectboxmigration"))
  // inject default module for all variant.
  releaseImplementation(project(":defaultmigration"))
  add("nightlyImplementation", project(":defaultmigration"))
  add("standaloneImplementation", project(":defaultmigration"))
  // inject migration module in playStore variant.
  add("playStoreImplementation", project(":objectboxmigration"))

  // Document File
  implementation(Libs.select_folder_document_file)
}

tasks.register("generateVersionCodeAndName") {
  val file = File("VERSION_INFO")
  if (!file.exists()) file.createNewFile()
  file.printWriter().use {
    it.print("${generateVersionName()}\n7${"".getVersionCode()}")
  }
}

tasks.register<RenameTarakFileTask>("renameTarakFile") {
  coreResDir.set(File("$rootDir/core/src/main/res"))
  trackedFiles.set(autoModifiedTrackedFiles)
  backupDir.set(trackedFileBackupDir)
  repoRootDir.set(project.rootDir)
}

project.objects.newInstance(TrackedFileRestoreRegistrar::class.java)
  .register(autoModifiedTrackedFiles, trackedFileBackupDir, project.rootDir)

gradle.projectsEvaluated {
  rootProject.allprojects.forEach { project ->
    project.tasks.configureEach {
      if (path != ":app:renameTarakFile") {
        dependsOn(":app:renameTarakFile")
      }
    }
  }
}
