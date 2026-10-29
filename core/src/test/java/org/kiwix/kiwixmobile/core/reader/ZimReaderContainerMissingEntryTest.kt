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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.kiwix.sharedFunctions.TestApplication
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class)
class ZimReaderContainerMissingEntryTest {
  private val zimFileReaderFactory: ZimFileReader.Factory = mockk()
  private val container = ZimReaderContainer(zimFileReaderFactory, Dispatchers.Unconfined)

  private fun openZimWithoutEntries() {
    val source: ZimReaderSource = mockk()
    val reader: ZimFileReader = mockk(relaxed = true)
    coEvery { source.exists(any()) } returns true
    coEvery { source.canOpenInLibkiwix(any()) } returns true
    coEvery { zimFileReaderFactory.create(source, any()) } returns reader
    every { reader.zimReaderSource } returns source
    every { reader.getItem(any()) } returns null
    runBlocking { container.setZimReaderSource(source) }
  }

  @Test
  fun `a missing entry gets a 404 with a MIME type and an empty body`() {
    openZimWithoutEntries()

    val response =
      container.load("https://kiwix.app/c.amazon-adsystem.com/aax2/apstag.js", emptyMap())

    assertEquals(404, response.statusCode)
    assertEquals("Not Found", response.reasonPhrase)
    assertEquals("text/plain", response.mimeType)
    assertEquals(-1, response.data.read())
  }

  @Test
  fun `a missing about blank iframe gets an empty html document`() {
    openZimWithoutEntries()

    val response = container.load("https://kiwix.app/mp_/about%3Ablank", emptyMap())

    assertEquals(200, response.statusCode)
    assertEquals("text/html", response.mimeType)
    assertEquals(-1, response.data.read())
  }

  @Test
  fun `a request without any open ZIM gets a 404`() {
    val response = container.load("https://kiwix.app/A/foo", emptyMap())

    assertEquals(404, response.statusCode)
    assertNotNull(response.mimeType)
  }
}
