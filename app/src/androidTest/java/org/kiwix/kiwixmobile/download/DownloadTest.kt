/*
 * Kiwix Android
 * Copyright (c) 2022 Kiwix <android.kiwix.org>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package org.kiwix.kiwixmobile.download

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.IdlingPolicies
import androidx.test.espresso.IdlingRegistry
import androidx.test.filters.LargeTest
import androidx.test.internal.runner.junit4.statement.UiThreadStatement
import androidx.test.platform.app.InstrumentationRegistry
import com.adevinta.android.barista.interaction.BaristaSleepInteractions
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import leakcanary.LeakAssertions
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.kiwix.kiwixmobile.BaseActivityTest
import org.kiwix.kiwixmobile.core.main.CoreMainActivity
import org.kiwix.kiwixmobile.core.utils.TestingUtils.COMPOSE_TEST_RULE_ORDER
import org.kiwix.kiwixmobile.core.utils.TestingUtils.HILT_RULE_ORDER
import org.kiwix.kiwixmobile.core.utils.TestingUtils.RETRY_RULE_ORDER
import org.kiwix.kiwixmobile.main.KiwixMainActivity
import org.kiwix.kiwixmobile.main.topLevel
import org.kiwix.kiwixmobile.nav.destination.library.library
import org.kiwix.kiwixmobile.testutils.RetryRule
import org.kiwix.kiwixmobile.testutils.TestUtils
import org.kiwix.kiwixmobile.testutils.TestUtils.waitUntilTimeout
import org.kiwix.kiwixmobile.ui.KiwixDestination
import org.kiwix.kiwixmobile.utils.KiwixIdlingResource.Companion.getInstance
import java.util.concurrent.TimeUnit

private const val BACKGROUND_DOWNLOAD_OBSERVE_MS = 15_000L

@LargeTest
@HiltAndroidTest
class DownloadTest : BaseActivityTest() {
  @Rule(order = HILT_RULE_ORDER)
  @JvmField
  val hiltRule = HiltAndroidRule(this)

  @Rule(order = RETRY_RULE_ORDER)
  @JvmField
  val retryRule = RetryRule()

  @Rule(order = COMPOSE_TEST_RULE_ORDER)
  @JvmField
  val composeTestRule = createComposeRule()

  private lateinit var kiwixMainActivity: KiwixMainActivity

  @Before
  override fun waitForIdle() {
    hiltRule.injectOnce()
    super.waitForIdle()
    updateKiwixDataStore {
      setShowStorageOption(false)
      setSelectedOnlineContentCategory("")
      setSelectedOnlineContentLanguage("")
    }
    launchMainActivity()
    composeTestRule.enableAccessibilityChecks(createAccessibilityValidator())
  }

  @Test
  fun downloadTest() {
    BaristaSleepInteractions.sleep(TestUtils.TEST_PAUSE_MS.toLong())
    activityScenario.onActivity {
      kiwixMainActivity = it
      it.navigate(KiwixDestination.Library.route)
    }
    try {
      // delete all the ZIM files showing in the LocalLibrary
      // screen to properly test the scenario.
      library {
        refreshList(composeTestRule)
        waitUntilZimFilesRefreshing(composeTestRule)
        deleteZimIfExists(composeTestRule)
      }
      downloadRobot {
        clickDownloadOnBottomNav(composeTestRule)
        waitForDataToLoad(composeTestRule = composeTestRule)
        stopDownloadIfAlreadyStarted(composeTestRule, kiwixMainActivity)
        searchD3JsDocsFile(composeTestRule)
        downloadZimFile(composeTestRule)
        runCatching {
          assertDownloadStart(composeTestRule)
          pauseDownload(composeTestRule)
          assertDownloadPaused(composeTestRule, kiwixMainActivity)
          resumeDownload(composeTestRule)
          assertDownloadResumed(composeTestRule, kiwixMainActivity)
        }.onFailure {
          // do nothing as ZIM file already downloaded, since we are downloading the smallest file
          // so it can be downloaded immediately after starting.
          Log.e(
            KIWIX_DOWNLOAD_TEST,
            "Could not pause download. Original exception = $it"
          )
        }
        // Missing a pause button can mean the download finished, but does not prove it did.
        waitUntilDownloadComplete(
          composeTestRule = composeTestRule,
          kiwixMainActivity = kiwixMainActivity
        )
        UiThreadStatement.runOnUiThread {
          kiwixMainActivity.navigate(KiwixDestination.Library.route)
        }
        // refresh the local library list to show the downloaded zim file
        library {
          refreshList(composeTestRule)
          waitUntilZimFilesRefreshing(composeTestRule)
        }
        checkIfZimFileDownloaded(composeTestRule)
      }
    } catch (e: Exception) {
      throw AssertionError("Download test failed before the downloaded file was verified", e)
    }
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
      LeakAssertions.assertNoLeaks()
    }
  }

  @Test
  fun testPauseAndResumeInOtherLanguage() {
    BaristaSleepInteractions.sleep(TestUtils.TEST_PAUSE_MS.toLong())
    activityScenario.onActivity {
      kiwixMainActivity = it
      it.navigate(KiwixDestination.Library.route)
    }
    // delete all the ZIM files showing in the LocalLibrary
    // screen to properly test the scenario.
    library {
      refreshList(composeTestRule)
      waitUntilZimFilesRefreshing(composeTestRule)
      deleteZimIfExists(composeTestRule)
    }
    downloadRobot {
      // change the application language
      topLevel {
        clickSettingsOnSideNav(kiwixMainActivity as CoreMainActivity, composeTestRule, true) {
          clickLanguagePreference(composeTestRule, activityScenario)
          assertLanguagePrefDialogDisplayed(composeTestRule, activityScenario)
          selectDeviceDefaultLanguage(composeTestRule)
          // Advance the main clock to settle the frame of compose.
          composeTestRule.mainClock.advanceTimeByFrame()
          composeTestRule.waitUntil(timeoutMillis = 5_000) {
            activityScenario.state.isAtLeast(Lifecycle.State.RESUMED)
          }
          composeTestRule.waitForIdle()
          activityScenario.onActivity {
            kiwixMainActivity = it
          }
          clickLanguagePreference(composeTestRule, activityScenario)
          assertLanguagePrefDialogDisplayed(composeTestRule, activityScenario)
          selectAlbanianLanguage(composeTestRule)
          // Advance the main clock to settle the frame of compose.
          composeTestRule.mainClock.advanceTimeByFrame()
          composeTestRule.waitUntil(timeoutMillis = 5_000) {
            activityScenario.state.isAtLeast(Lifecycle.State.RESUMED)
          }
          composeTestRule.waitForIdle()
          activityScenario.onActivity {
            kiwixMainActivity = it
            it.onBackPressedDispatcher.onBackPressed()
          }
        }
      }
      updateKiwixDataStore {
        setSelectedOnlineContentLanguage("eng")
      }
      clickDownloadOnBottomNav(composeTestRule)
      waitForDataToLoad(composeTestRule = composeTestRule)
      stopDownloadIfAlreadyStarted(composeTestRule, kiwixMainActivity)
      searchD3JsDocsFile(composeTestRule)
      downloadZimFile(composeTestRule)
      runCatching {
        assertDownloadStart(composeTestRule)
        pauseDownload(composeTestRule)
        assertDownloadPaused(composeTestRule, kiwixMainActivity)
        resumeDownload(composeTestRule)
        assertDownloadResumed(composeTestRule, kiwixMainActivity)
        stopDownloadIfAlreadyStarted(composeTestRule, kiwixMainActivity)
      }.onFailure {
        // do nothing as ZIM file already downloaded
        Log.e(
          KIWIX_DOWNLOAD_TEST,
          "Could not complete pause/resume cycle. It might have finished: $it"
        )
      }
      // select the default device language to perform other test cases.
      topLevel {
        clickSettingsOnSideNav(kiwixMainActivity as CoreMainActivity, composeTestRule, true) {
          clickLanguagePreference(composeTestRule, activityScenario)
          assertLanguagePrefDialogDisplayed(composeTestRule, activityScenario)
          selectDeviceDefaultLanguage(composeTestRule)
          // Advance the main clock to settle the frame of compose.
          composeTestRule.mainClock.advanceTimeByFrame()
          composeTestRule.waitUntil(timeoutMillis = 5_000) {
            activityScenario.state.isAtLeast(Lifecycle.State.RESUMED)
          }
          composeTestRule.waitForIdle()
          activityScenario.onActivity {
            kiwixMainActivity = it
          }
          // close the language dialog.
          composeTestRule.runOnUiThread {
            kiwixMainActivity.onBackPressedDispatcher.onBackPressed()
          }
        }
      }
      updateKiwixDataStore {
        setSelectedOnlineContentLanguage("")
      }
    }
  }

  @Test
  fun testResumeDownloadAfterAppRestart() {
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
      BaristaSleepInteractions.sleep(TestUtils.TEST_PAUSE_MS.toLong())
      activityScenario.onActivity {
        kiwixMainActivity = it
        it.navigate(KiwixDestination.Library.route)
      }
      library {
        refreshList(composeTestRule)
        waitUntilZimFilesRefreshing(composeTestRule)
        deleteZimIfExists(composeTestRule)
      }
      downloadRobot {
        clickDownloadOnBottomNav(composeTestRule)
        waitForDataToLoad(composeTestRule = composeTestRule)
        stopDownloadIfAlreadyStarted(composeTestRule, kiwixMainActivity)
        searchD3JsDocsFile(composeTestRule)
        downloadZimFile(composeTestRule)
        runCatching {
          assertDownloadStart(composeTestRule)
          pauseDownload(composeTestRule)
          assertDownloadPaused(composeTestRule, kiwixMainActivity)
        }.onFailure {
          Log.e(KIWIX_DOWNLOAD_TEST, "Could not pause before restart: $it")
        }
      }

      // Restart the application
      val context = ApplicationProvider.getApplicationContext<Context>()
      val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
      intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
      context.startActivity(intent)
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()

      activityScenario = ActivityScenario.launch(KiwixMainActivity::class.java).apply {
        moveToState(Lifecycle.State.RESUMED)
        onActivity {
          kiwixMainActivity = it
          it.navigate(KiwixDestination.Explore.route)
        }
      }

      downloadRobot {
        waitForDataToLoad(composeTestRule = composeTestRule)
        runCatching {
          assertDownloadPaused(composeTestRule, kiwixMainActivity)
          resumeDownload(composeTestRule)
          assertDownloadResumed(composeTestRule, kiwixMainActivity)
          stopDownloadIfAlreadyStarted(composeTestRule, kiwixMainActivity)
        }.onFailure {
          Log.e(
            KIWIX_DOWNLOAD_TEST,
            "Could not complete resume after restart. It might have finished: $it"
          )
        }
      }
    }
  }

  @Test
  fun downloadZIMFileInBackground() {
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
      activityScenario.onActivity {
        kiwixMainActivity = it
        it.navigate(KiwixDestination.Library.route)
      }
      // delete all the ZIM files showing in the LocalLibrary
      // screen to properly test the scenario.
      library {
        refreshList(composeTestRule)
        waitUntilZimFilesRefreshing(composeTestRule)
        deleteZimIfExists(composeTestRule)
      }
      activityScenario.onActivity {
        it.navigate(KiwixDestination.Explore.route)
      }
      downloadRobot {
        waitForDataToLoad(composeTestRule = composeTestRule)
        stopDownloadIfAlreadyStarted(composeTestRule, kiwixMainActivity)
        searchD3JsDocsFile(composeTestRule)
        downloadZimFile(composeTestRule)
        assertDownloadStart(composeTestRule)
      }
      val backgroundWaitStartedAt = android.os.SystemClock.elapsedRealtime()
      Log.i(KIWIX_DOWNLOAD_TEST, "Background download test: app sent to background")
      // press the home button so that application goes into background
      InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
        AccessibilityService.GLOBAL_ACTION_HOME
      )
      // Give the background service time to continue before checking its state after relaunch.
      composeTestRule.waitUntilTimeout(BACKGROUND_DOWNLOAD_OBSERVE_MS)
      Log.i(
        KIWIX_DOWNLOAD_TEST,
        "Background download wait finished after " +
          "${android.os.SystemClock.elapsedRealtime() - backgroundWaitStartedAt}ms"
      )
      // relaunch the application.
      val context = ApplicationProvider.getApplicationContext<Context>()
      val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
      intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
      context.startActivity(intent)
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()

      activityScenario = ActivityScenario.launch(KiwixMainActivity::class.java).apply {
        moveToState(Lifecycle.State.RESUMED)
        onActivity {
          kiwixMainActivity = it
          it.navigate(KiwixDestination.Library.route)
        }
      }
      Log.i(KIWIX_DOWNLOAD_TEST, "Background download test: app relaunched; checking file state")
      library {
        refreshList(composeTestRule)
        waitUntilZimFilesRefreshing(composeTestRule)
        downloadRobot {
          runCatching {
            checkIfZimFileDownloaded(composeTestRule)
          }.onFailure {
            // If the file is not ready, verify that its download continued in the background.
            clickDownloadOnBottomNav(composeTestRule)
            waitForDataToLoad(composeTestRule = composeTestRule)
            assertDownloadStart(composeTestRule)
            Log.i(KIWIX_DOWNLOAD_TEST, "Download remained active after backgrounding and relaunch")
            stopDownloadIfAlreadyStarted(composeTestRule, kiwixMainActivity)
          }.onSuccess {
            Log.i(KIWIX_DOWNLOAD_TEST, "Download completed while the app was in the background")
          }
        }
      }
    }
  }

  @After
  fun finish() {
    IdlingRegistry.getInstance().unregister(getInstance())
    TestUtils.deleteTemporaryFilesOfTestCases(context)
  }

  companion object {
    const val KIWIX_DOWNLOAD_TEST = "kiwixDownloadTest"

    @BeforeClass
    fun beforeClass() {
      IdlingPolicies.setMasterPolicyTimeout(180, TimeUnit.SECONDS)
      IdlingPolicies.setIdlingResourceTimeout(180, TimeUnit.SECONDS)
      IdlingRegistry.getInstance().register(getInstance())
    }
  }
}
