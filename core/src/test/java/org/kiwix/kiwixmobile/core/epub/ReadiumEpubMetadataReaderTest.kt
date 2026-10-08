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

package org.kiwix.kiwixmobile.core.epub

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kiwix.kiwixmobile.core.epub.reader.EpubFixture
import org.kiwix.kiwixmobile.core.epub.reader.EpubPublicationOpener
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R])
class ReadiumEpubMetadataReaderTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private val reader = ReadiumEpubMetadataReader(
    EpubPublicationOpener(ApplicationProvider.getApplicationContext(), Dispatchers.IO)
  )

  @Test
  fun `reads identifier, title, authors, language and cover through Readium`() = runTest {
    val info = reader.read(EpubFixture.write(tmp.newFile("book.epub")))

    assertNotNull(info)
    assertEquals(EpubFixture.IDENTIFIER, info!!.identifier)
    assertEquals(EpubFixture.TITLE, info.title)
    assertEquals(listOf("Jane Doe", "John Roe"), info.authors)
    assertEquals("en", info.language)
    assertTrue(info.cover?.isNotEmpty() == true)
  }

  @Test
  fun `returns null for a file that is not an epub`() = runTest {
    val junk = File(tmp.root, "junk.epub").apply { writeText("not a zip") }
    assertNull(reader.read(junk))
    assertNull(reader.read(File(tmp.root, "missing.epub")))
  }
}
