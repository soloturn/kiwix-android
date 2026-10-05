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

import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Build
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kiwix.sharedFunctions.TestApplication
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R], application = TestApplication::class)
class ZimReaderSourceLazyDescriptorTest {
  private val originalOpener = ZimReaderSource.descriptorOpener
  private val opened = mutableListOf<Uri>()
  private val descriptor: AssetFileDescriptor = mockk(relaxed = true)

  @Before
  fun setUp() {
    ZimReaderSource.descriptorOpener = { uri ->
      opened += uri
      listOf(descriptor)
    }
  }

  @After
  fun tearDown() {
    ZimReaderSource.descriptorOpener = originalOpener
  }

  @Test
  fun constructingManyUriSourcesOpensNoDescriptor() {
    val sources = (1..1000).map { ZimReaderSource(Uri.parse("content://tree/book$it.zim")) }
    assertTrue(opened.isEmpty())
    assertEquals(1000, sources.toSet().size)
  }

  @Test
  fun descriptorsOpenOnceOnFirstUseAndReopenAfterRelease() {
    val source = ZimReaderSource(Uri.parse("content://tree/book.zim"))
    assertEquals(listOf(descriptor), source.assetFileDescriptorList)
    assertEquals(listOf(descriptor), source.assetFileDescriptorList)
    assertEquals(1, opened.size)

    source.releaseDescriptors()
    verify { descriptor.close() }
    source.assetFileDescriptorList
    assertEquals(2, opened.size)
  }

  @Test
  fun equalityOfUriSourcesDoesNotOpenDescriptors() {
    val first = ZimReaderSource(Uri.parse("content://tree/same.zim"))
    val second = ZimReaderSource(Uri.parse("content://tree/same.zim"))
    assertEquals(first, second)
    assertEquals(first.hashCode(), second.hashCode())
    assertTrue(opened.isEmpty())
  }

  @Test
  fun libraryBookWithUriPathYieldsUriSource() {
    val book = org.kiwix.kiwixmobile.core.entity.LibkiwixBook(_path = "content://tree/x.zim")
    assertEquals("content://tree/x.zim", book.zimReaderSource.uri.toString())
    assertTrue(opened.isEmpty())
  }
}
