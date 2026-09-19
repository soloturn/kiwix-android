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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    // A real, thread-pool-backed dispatcher - this test exercises genuine cross-thread
    // lock contention (WebView thread vs. a setter thread), which the virtual-time
    // StandardTestDispatcher used by the other test can't drive from a foreign thread.
    val realDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
    val container = ZimReaderContainer(zimFileReaderFactory, realDispatcher)

    val oldSource: ZimReaderSource = mockk()
    val newSource: ZimReaderSource = mockk()
    val oldReader: ZimFileReader = mockk(relaxed = true)
    val newReader: ZimFileReader = mockk(relaxed = true)
    coEvery { oldSource.exists(any()) } returns true
    coEvery { oldSource.canOpenInLibkiwix(any()) } returns true
    coEvery { newSource.exists(any()) } returns true
    coEvery { newSource.canOpenInLibkiwix(any()) } returns true
    coEvery { zimFileReaderFactory.create(oldSource, any()) } returns oldReader
    coEvery { zimFileReaderFactory.create(newSource, any()) } returns newReader

    runBlocking { container.setZimReaderSource(oldSource) }

    val readStarted = CountDownLatch(1)
    val releaseRead = CountDownLatch(1)
    every { oldReader.isRedirect(any()) } answers {
      readStarted.countDown()
      releaseRead.await(2, TimeUnit.SECONDS)
      false
    }

    val executor = Executors.newFixedThreadPool(2)
    try {
      val readFuture = executor.submit { container.isRedirect("https://kiwix.app/A/foo") }
      assertEquals(true, readStarted.await(2, TimeUnit.SECONDS))

      val setterFuture = executor.submit {
        runBlocking { container.setZimReaderSource(newSource) }
      }

      // The setter is blocked on the write lock while the read above is in flight,
      // so dispose() must not have run yet.
      Thread.sleep(200)
      verify(exactly = 0) { oldReader.dispose() }

      releaseRead.countDown()
      readFuture.get(2, TimeUnit.SECONDS)
      setterFuture.get(2, TimeUnit.SECONDS)

      verify(exactly = 1) { oldReader.dispose() }
      assertEquals(newReader, container.withReaderBlocking { it })
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `setZimReaderSource calls are serialized - the second replaces the first`() =
    runTest(mainDispatcherRule.dispatcher) {
      val firstSource: ZimReaderSource = mockk()
      val secondSource: ZimReaderSource = mockk()
      val firstReader: ZimFileReader = mockk(relaxed = true)
      val secondReader: ZimFileReader = mockk(relaxed = true)
      val firstGate = CompletableDeferred<Unit>()
      coEvery { firstSource.exists(any()) } coAnswers {
        firstGate.await()
        true
      }
      coEvery { firstSource.canOpenInLibkiwix(any()) } returns true
      coEvery { secondSource.exists(any()) } returns true
      coEvery { secondSource.canOpenInLibkiwix(any()) } returns true
      coEvery { zimFileReaderFactory.create(firstSource, any()) } returns firstReader
      coEvery { zimFileReaderFactory.create(secondSource, any()) } returns secondReader

      // Starts, but suspends inside the lock on firstGate before it can finish.
      val firstJob = launch { container.setZimReaderSource(firstSource) }
      runCurrent()

      // Queues behind the first call - can't even start its own native open yet,
      // since setZimReaderSource is now fully serialized by a single Mutex.
      val secondJob = launch { container.setZimReaderSource(secondSource) }
      runCurrent()

      firstGate.complete(Unit)
      firstJob.join()
      secondJob.join()

      assertEquals(secondReader, container.withReaderBlocking { it })
      verify { firstReader.dispose() }
    }
}
