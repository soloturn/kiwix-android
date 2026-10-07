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

package org.kiwix.kiwixmobile.core.utils.files

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubFileUtilsTest {
  @TempDir
  lateinit var dir: File

  private fun zip(vararg entries: Pair<String, String>): ByteArray =
    ByteArrayOutputStream().also { out ->
      ZipOutputStream(out).use { z ->
        entries.forEach { (name, body) ->
          z.putNextEntry(ZipEntry(name))
          z.write(body.toByteArray())
          z.closeEntry()
        }
      }
    }.toByteArray()

  private fun write(name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }

  @Test
  fun `isEpubFile matches extension case-insensitively`() {
    assertTrue(isEpubFile("/a/b/book.epub"))
    assertTrue(isEpubFile("Book.EPUB"))
    assertTrue(isEpubFile(File("x.y.epub")))
    assertFalse(isEpubFile("book.zim"))
    assertFalse(isEpubFile("book.epub.zim"))
    assertFalse(isEpubFile("epub"))
    assertFalse(isEpubFile(""))
  }

  @Test
  fun `valid with mimetype entry`() {
    val f = write("a.epub", zip("mimetype" to "application/epub+zip"))
    assertTrue(isValidEpubFile(f))
  }

  @Test
  fun `valid with container xml only`() {
    val f = write("a.epub", zip("META-INF/container.xml" to "<container/>"))
    assertTrue(isValidEpubFile(f))
  }

  @Test
  fun `valid with container xml after other entries`() {
    val f = write("a.epub", zip("x.txt" to "x", "META-INF/container.xml" to "<c/>"))
    assertTrue(isValidEpubFile(f))
  }

  @Test
  fun `invalid zip without epub entries`() {
    assertFalse(isValidEpubFile(write("a.epub", zip("readme.txt" to "hi"))))
  }

  @Test
  fun `invalid when mimetype is something else and no container`() {
    assertFalse(isValidEpubFile(write("a.epub", zip("mimetype" to "application/zip"))))
  }

  @Test
  fun `invalid non-zip, truncated, empty and missing files`() {
    assertFalse(isValidEpubFile(write("a.epub", "not a zip at all".toByteArray())))
    assertFalse(isValidEpubFile(write("b.epub", byteArrayOf())))
    assertFalse(isValidEpubFile(write("c.epub", byteArrayOf(0x50, 0x4B))))
    assertFalse(isValidEpubFile(File(dir, "missing.epub")))
    assertFalse(isValidEpubFile(dir))
  }

  @Test
  fun `stream variant works without mark support`() {
    val bytes = zip("mimetype" to "application/epub+zip")
    val noMark = object : java.io.InputStream() {
      private val s = bytes.inputStream()
      override fun read() = s.read()
    }
    assertTrue(isValidEpubStream(noMark))
  }
}
