/*
 * Kiwix Android
 * Copyright (c) 2026 Kiwix <android.kiwix.org>
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

package org.kiwix.kiwixmobile.core.reader

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.kiwix.sharedFunctions.MainDispatcherRule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Guards against the JNI use-after-free race described in
 * https://github.com/kiwix/kiwix-android/issues/5070#1: the WebView's Chromium worker
 * thread can be mid-read (isRedirect/getRedirect/load) while another thread swaps in a
 * new ZimFileReader, disposing the native Archive the worker thread is still using.
 */
class ZimReaderContainerTest {
  @JvmField
  @RegisterExtension
  val mainDispatcherRule = MainDispatcherRule()

  private val zimFileReaderFactory: ZimFileReader.Factory = mockk()
  private val container =
    ZimReaderContainer(zimFileReaderFactory, mainDispatcherRule.dispatcher)

  @Test
  fun `dispose does not run while a read is in flight, and always runs eventually`() {
    val oldReader: ZimFileReader = mockk(relaxed = true)
    val readStarted = CountDownLatch(1)
    val releaseRead = CountDownLatch(1)
    every { oldReader.isRedirect(any()) } answers {
      readStarted.countDown()
      releaseRead.await(2, TimeUnit.SECONDS)
      false
    }
    container.zimFileReader = oldReader

    val executor = Executors.newFixedThreadPool(2)
    try {
      val readFuture = executor.submit { container.isRedirect("https://kiwix.app/A/foo") }
      assertEquals(true, readStarted.await(2, TimeUnit.SECONDS))

      val newReader: ZimFileReader = mockk(relaxed = true)
      val setterFuture = executor.submit { container.zimFileReader = newReader }

      // The setter is blocked on the write lock while the read above is in flight,
      // so dispose() must not have run yet.
      Thread.sleep(200)
      verify(exactly = 0) { oldReader.dispose() }

      releaseRead.countDown()
      readFuture.get(2, TimeUnit.SECONDS)
      setterFuture.get(2, TimeUnit.SECONDS)

      verify(exactly = 1) { oldReader.dispose() }
      assertEquals(newReader, container.zimFileReader)
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `a stale setZimReaderSource call does not clobber a newer one`() =
    runTest(mainDispatcherRule.dispatcher) {
      val staleSource: ZimReaderSource = mockk()
      val freshSource: ZimReaderSource = mockk()
      val staleReader: ZimFileReader = mockk(relaxed = true)
      val freshReader: ZimFileReader = mockk(relaxed = true)
      val staleGate = CompletableDeferred<Unit>()
      coEvery { staleSource.exists(any()) } coAnswers {
        staleGate.await()
        true
      }
      coEvery { staleSource.canOpenInLibkiwix(any()) } returns true
      coEvery { freshSource.exists(any()) } returns true
      coEvery { freshSource.canOpenInLibkiwix(any()) } returns true
      coEvery { zimFileReaderFactory.create(staleSource, any()) } returns staleReader
      coEvery { zimFileReaderFactory.create(freshSource, any()) } returns freshReader

      // Starts, but suspends on staleGate before it can apply its result.
      val staleJob = launch { container.setZimReaderSource(staleSource) }
      runCurrent()

      // Completes fully while the stale call is still suspended.
      launch { container.setZimReaderSource(freshSource) }.join()
      assertEquals(freshReader, container.zimFileReader)

      // The stale call now finishes and tries to apply its now-superseded result.
      staleGate.complete(Unit)
      staleJob.join()

      assertEquals(freshReader, container.zimFileReader)
      verify { staleReader.dispose() }
    }
}
