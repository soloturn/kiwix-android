/*
 * Kiwix Android
 * Copyright (c) 2019 Kiwix <android.kiwix.org>
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
package org.kiwix.kiwixmobile.testutils

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.core.content.ContextCompat
import androidx.test.core.app.canTakeScreenshot
import androidx.test.core.app.takeScreenshot
import androidx.test.espresso.matcher.BoundedMatcher
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.By.textContains
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject
import androidx.test.uiautomator.UiObjectNotFoundException
import androidx.test.uiautomator.UiSelector
import okhttp3.OkHttpClient
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.kiwix.kiwixmobile.core.data.remote.UserAgentInterceptor
import org.kiwix.kiwixmobile.core.di.modules.USER_AGENT
import org.kiwix.kiwixmobile.core.entity.LibkiwixBook
import org.kiwix.kiwixmobile.core.ui.components.SWIPE_REFRESH_TESTING_TAG
import org.kiwix.kiwixmobile.core.utils.files.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Created by mhutti1 on 07/04/17.
 */
object TestUtils {
  private const val TAG = "TESTUTILS"

  const val TEST_PAUSE_MS = 3000
  const val TEST_PAUSE_MS_FOR_SEARCH_TEST = 1000

  // WebView renderer respawns (Activity torn down/recreated between tests -> the
  // sandboxed renderer process bound to it dies and is silently relaunched; harmless,
  // but the ~1-2s respawn+reload can outrun a 3s wait) can delay search results.
  // See run https://github.com/soloturn/kiwix-android/actions/runs/35167290688.
  // 6000ms was still too tight: run 35260139385 timed out on this wait in all 3
  // RetryRule attempts back-to-back, meaning the underlying CI stall outlasts 6s.
  // 15000ms wasn't enough either, three separate times - run 35308243036's
  // resource-diag showed why: host load average hit 6.37 on a 4-vCPU runner
  // right before an 88-SECOND total logcat silence (confirmed via the raw
  // job log, not just this diagnostic). No fixed wait survives a stall that
  // severe; this only raises the bar against smaller, more common spikes.
  // The real mitigations are the guest-load-based stall capture and reduced
  // Gradle worker contention added alongside this change.
  const val TEST_PAUSE_MS_FOR_SEARCH_RESULTS = 30_000L
  const val TEST_PAUSE_MS_FOR_DOWNLOAD_TEST = 10000L

  // longClickOnSaveBookmarkImage's own wait - kept separate from the
  // shared constant above since that one has ~30 unrelated call sites.
  const val TEST_PAUSE_MS_FOR_BOOKMARK_BUTTON = 20_000L

  // zimReaderContainer.zimFileReader != null polls: run 35415742112 timed out
  // 3/3 at 10s. 20s wasn't enough either - run 35833790955 timed out 20/20
  // retries across 5 jobs. Since 72dc991e5, closeZimBook() genuinely awaits
  // the write lock for dispose(), which can queue behind a straggling
  // WebView shouldInterceptRequest callback that outlives destroyAllTabs()
  // (Android doesn't guarantee those stop at WebView.destroy()) - correct,
  // but slower than the old fire-and-forget close.
  const val TEST_PAUSE_MS_FOR_ZIM_FILE_OPEN = 40_000L
  const val TEST_PAUSE_MS_FOR_SNACKBAR = 6000L
  const val FIVE_SECOND_DELAY = 5000L
  const val FIFTEEN_SECOND_DELAY = 15_000L

  // testFlakyView's retries need to survive a transient condition that takes
  // actual time to resolve (e.g. a WebView renderer respawn); see the retry
  // loop below for why this delay exists.
  private const val RETRY_DELAY_FOR_FLAKY_VIEW_MS = 500L

  // Own retry budget for assertZimFileLoadedIntoTheReader - default 2.5s was
  // too tight for WebView content load under CI load (run 35424852955).
  const val RETRY_COUNT_FOR_WEBVIEW_CONTENT_LOAD = 40
  private const val READ_AND_CALL_TIMEOUT = 5L
  private const val CONNECTION_TIMEOUT = 1L

  /*
    TEST_PAUSE_MS is used as such:
        BaristaSleepInteractions.sleep(TEST_PAUSE_MS);
    The number 250 is fairly arbitrary. I found 100 to be insufficient,
        and 250 seems to work on all devices I've tried.

    The sleep combats an intermittent issue caused by
        tests executing before the app/activity is ready.
    This isn't necessary on all devices (particularly more recent ones),
        however I'm unsure if
    it's speed related, or Android Version related.
   */

  private fun hasReadExternalStoragePermission(): Boolean =
    ContextCompat.checkSelfPermission(
      InstrumentationRegistry.getInstrumentation().targetContext,
      Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

  private fun hasWriteExternalStoragePermission(): Boolean =
    ContextCompat.checkSelfPermission(
      InstrumentationRegistry.getInstrumentation().targetContext,
      Manifest.permission.WRITE_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

  @JvmStatic fun hasStoragePermission() =
    Build.VERSION.SDK_INT > Build.VERSION_CODES.M &&
      hasReadExternalStoragePermission() &&
      hasWriteExternalStoragePermission()

  @JvmStatic fun allowStoragePermissionsIfNeeded() {
    if (!hasStoragePermission()) {
      val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
      val allowPermissions =
        device.findObject(
          UiSelector()
            .clickable(true)
            .checkable(false)
            .index(1)
        )
      if (allowPermissions.exists()) {
        try {
          allowPermissions.click()
        } catch (e: UiObjectNotFoundException) {
          Log.w(TAG, "Unable to find allow permission dialog", e)
        }
      }
    }
  }

  @JvmStatic fun captureAndSaveScreenshot(name: String?) {
    val screenshotDir =
      File(
        Environment.getExternalStorageDirectory().toString() +
          "/Android/data/KIWIXTEST/Screenshots"
      )
    if (!screenshotDir.exists()) {
      if (!screenshotDir.mkdirs()) {
        return
      }
    }
    val timestamp = SimpleDateFormat("ddMMyyyy_HHmm").format(Date())
    val fileName = "TEST_${timestamp}_$name.png"
    val outFile = File(screenshotDir.path + File.separator + fileName)
    if (!canTakeScreenshot()) return
    val screenshot = takeScreenshot()
    var fos: OutputStream? = null
    try {
      fos = FileOutputStream(outFile)
      screenshot.compress(Bitmap.CompressFormat.PNG, 90, fos)
    } catch (e: FileNotFoundException) {
      Log.w(TAG, "Unable to create file $outFile", e)
    } finally {
      fos?.close()
    }
  }

  @JvmStatic fun withContent(content: String): Matcher<Any?> {
    return object : BoundedMatcher<Any?, Any?>(Any::class.java) {
      public override fun matchesSafely(myObj: Any?): Boolean {
        if (myObj !is LibkiwixBook) {
          return false
        }
        return if (myObj.url != null) {
          myObj.url?.contains(content) == true
        } else {
          myObj.file?.path?.contains(content) == true
        }
      }

      override fun describeTo(description: Description) {
        description.appendText("with content '$content'")
      }
    }
  }

  @JvmStatic fun getResourceString(id: Int): String {
    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    return targetContext.resources.getString(id)
  }

  @JvmStatic
  fun isSystemUINotRespondingDialogVisible(uiDevice: UiDevice) =
    uiDevice.findObject(textContains("System UI isn't responding")) != null ||
      uiDevice.findObject(textContains("Process system isn't responding")) != null ||
      uiDevice.findObject(textContains("Launcher isn't responding")) != null ||
      uiDevice.findObject(textContains("Wait")) != null ||
      uiDevice.findObject(textContains("WAIT")) != null ||
      uiDevice.findObject(textContains("OK")) != null ||
      uiDevice.findObject(textContains("Ok")) != null ||
      uiDevice.findObject(By.clazz("android.app.Dialog")) != null

  @JvmStatic
  fun closeSystemDialogs(context: Context?, uiDevice: UiDevice) {
    // Close any system dialogs visible on Android versions below 12 by broadcasting
    context?.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
    // Press the back button as most dialogs can be closed by doing so
    uiDevice.pressBack()
    try {
      // Click on the button of system dialog (Especially applicable to non-closable dialogs)
      val waitButton = getSystemDialogButton(uiDevice)
      if (waitButton?.exists() == true) {
        uiDevice.click(waitButton.bounds.centerX(), waitButton.bounds.centerY())
      }
    } catch (ignore: Exception) {
      Log.d(
        TAG,
        "Couldn't click on Wait/OK button, probably no system dialog is " +
          "visible with Wait/OK button \n$ignore"
      )
    }
  }

  private fun getSystemDialogButton(uiDevice: UiDevice): UiObject? {
    // All possible button text based on different Android versions.
    val possibleButtonTextList = arrayOf("Wait", "WAIT", "OK", "Ok")
    return possibleButtonTextList
      .asSequence()
      .map { uiDevice.findObject(UiSelector().textContains(it)) }
      .firstOrNull(UiObject::exists)
  }

  @JvmStatic
  fun testFlakyView(
    action: () -> Unit,
    retryCount: Int = 5
  ) {
    try {
      action()
    } catch (ignore: Throwable) {
      if (retryCount > 0) {
        // Retries used to fire back-to-back with no delay, so they burned no real
        // wall-clock time - useless against a transient condition that takes actual
        // time to resolve (e.g. the WebView renderer respawn documented in
        // search-results-webview-respawn-timeout.md, ~1-2s). Run 35294866469's
        // testBookmarks hit exactly this: assertZimFileLoadedIntoTheReader's
        // onWebView() check failed outright right after a logged renderer crash,
        // all 5 instant retries still found no WebView, and only RetryRule's much
        // coarser whole-test retry (which genuinely takes real time via setup) let
        // it eventually pass. Give each retry real time to matter.
        SystemClock.sleep(RETRY_DELAY_FOR_FLAKY_VIEW_MS)
        testFlakyView(action, retryCount - 1)
      } else {
        throw ignore // No more retries, rethrow the exception
      }
    }
  }

  @JvmStatic
  fun deleteTemporaryFilesOfTestCases(context: Context) {
    context
      .getExternalFilesDirs(null)
      .filterNotNull()
      .forEach(::deleteAllFilesInDirectory)
    ContextWrapper(context)
      .externalMediaDirs
      .filterNotNull()
      .forEach(::deleteAllFilesInDirectory)
  }

  private fun deleteAllFilesInDirectory(directory: File) {
    if (directory.isDirectory) {
      directory.listFiles()?.forEach { file ->
        if (file.isDirectory) {
          // Recursively delete files in subdirectories, but keep the
          // subdirectory itself - other code may rely on it still existing.
          deleteAllFilesInDirectory(file)
        } else {
          file.delete()
        }
      }
    }
  }

  @JvmStatic
  fun getZimFileFromResourceFolder(
    context: Context,
    zimFileName: String,
    destinationDirectory: File = context.getExternalFilesDirs(null)[0]
  ): File {
    val loadFileStream =
      requireNotNull(javaClass.classLoader?.getResourceAsStream(zimFileName)) {
        "Unable to load $zimFileName. Please ensure it exists in the resources folder."
      }
    val zimFile = File(destinationDirectory, zimFileName)
    // Write to a temp file and rename into place instead of writing zimFile directly -
    // a reader that opens zimFile mid-copy (e.g. a prior test's reader, still disposing
    // in the background when RetryRule fires a retry that overwrites the same path) would
    // see a truncated file ("zim-file is too small to contain a header"). rename(2) swaps
    // the directory entry atomically, so any open() after this call sees a complete file.
    val tempFile = File(destinationDirectory, "$zimFileName.tmp")
    loadFileStream.use { inputStream ->
      tempFile.outputStream().use { output ->
        inputStream.copyTo(output)
      }
    }
    check(tempFile.renameTo(zimFile)) { "Could not move $tempFile to $zimFile" }
    return zimFile
  }

  @JvmStatic
  @Singleton
  fun getOkkHttpClientForTesting(): OkHttpClient =
    OkHttpClient()
      .newBuilder()
      .followRedirects(true)
      .followSslRedirects(true)
      .connectTimeout(CONNECTION_TIMEOUT, TimeUnit.MINUTES)
      .readTimeout(READ_AND_CALL_TIMEOUT, TimeUnit.MINUTES)
      .callTimeout(READ_AND_CALL_TIMEOUT, TimeUnit.MINUTES)
      .addNetworkInterceptor(UserAgentInterceptor(USER_AGENT))
      .build()

  fun ComposeContentTestRule.refresh() {
    onNodeWithTag(SWIPE_REFRESH_TESTING_TAG)
      .performTouchInput { swipeDown() }
  }

  /**
   * Waits for [testTag] to be displayed, same as
   * `waitUntil { onNodeWithTag(testTag).isDisplayed() }`.
   *
   * Screens with a collapsing TopAppBar/BottomAppBar (`BottomAppBarScrollBehavior`,
   * `TopAppBarScrollBehavior`) can be left with the bar scrolled off-screen after a test performs
   * a *programmatic* scroll (e.g. `performScrollToIndex`/`animateScrollToItem`), because those
   * APIs move the list directly without dispatching nested-scroll deltas the way a real touch
   * drag does - the app's own `LocalLibraryBackToTopButton` has to manually reset the scroll
   * behaviour's offsets for the same reason. If the node isn't displayed in time, this performs a
   * real swipeDown() gesture (which does dispatch nested scroll and so un-collapses the bars,
   * exactly like a user's own swipe would) and retries once before giving up.
   */
  fun ComposeContentTestRule.waitUntilDisplayedWithScrollNudge(
    testTag: String,
    timeoutMillis: Long = TEST_PAUSE_MS_FOR_DOWNLOAD_TEST
  ) {
    try {
      waitUntil(timeoutMillis) { onNodeWithTag(testTag).isDisplayed() }
    } catch (_: ComposeTimeoutException) {
      Log.w(
        "TestUtils",
        "'$testTag' was not displayed after ${timeoutMillis}ms. Nudging with a swipe " +
          "gesture in case a collapsing app bar was left scrolled off-screen, then retrying."
      )
      onRoot().performTouchInput { swipeDown() }
      waitForIdle()
      waitUntil(timeoutMillis) { onNodeWithTag(testTag).isDisplayed() }
    }
  }

  fun ComposeContentTestRule.waitUntilTimeout(timeoutMillis: Long = TEST_PAUSE_MS.toLong()) {
    AsyncTimer.start(timeoutMillis)
    waitUntil(
      condition = { AsyncTimer.expired },
      timeoutMillis = timeoutMillis + 1000
    )
  }

  object AsyncTimer {
    var expired = false
    fun start(delay: Long = 1000) {
      expired = false
      val timerTask = TimerTaskImpl {
        expired = true
      }
      Timer().schedule(timerTask, delay)
    }
  }

  class TimerTaskImpl(private val runnable: Runnable) : TimerTask() {
    override fun run() {
      runnable.run()
    }
  }
}
