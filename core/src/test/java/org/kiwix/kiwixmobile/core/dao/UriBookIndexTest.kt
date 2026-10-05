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

package org.kiwix.kiwixmobile.core.dao

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kiwix.kiwixmobile.core.entity.LibkiwixBook
import org.kiwix.sharedFunctions.TestApplication
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R], application = TestApplication::class)
class UriBookIndexTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun book(id: String, uri: String) = LibkiwixBook(
    _id = id,
    _title = "Title $id",
    _language = "eng",
    _size = "1024",
    _favicon = "base64",
    _path = uri
  )

  @Test
  fun booksSurviveAReload() {
    val file = File(tempFolder.root, "ZIMFiles/uri_library.json")
    UriBookIndex(file).put(book("a", "content://tree/a.zim"))

    val reloaded = UriBookIndex(file).all().single()
    assertEquals("a", reloaded.id)
    assertEquals("Title a", reloaded.title)
    assertEquals("content://tree/a.zim", reloaded.path)
    assertEquals("base64", reloaded.favicon)
    assertEquals("content://tree/a.zim", reloaded.zimReaderSource.uri.toString())
  }

  @Test
  fun putReplacesSameIdAndRemoveForgets() {
    val index = UriBookIndex(File(tempFolder.root, "uri_library.json"))
    index.put(book("a", "content://tree/old.zim"))
    index.put(book("a", "content://tree/new.zim"))
    assertEquals("content://tree/new.zim", index.all().single().path)

    assertTrue(index.remove(listOf("a")))
    assertFalse(index.contains("a"))
    assertFalse(index.remove(listOf("a")))
  }

  @Test
  fun corruptFileYieldsEmptyIndex() {
    val file = File(tempFolder.root, "uri_library.json").apply { writeText("{not json") }
    assertTrue(UriBookIndex(file).all().isEmpty())
  }
}
