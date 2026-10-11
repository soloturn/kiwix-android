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

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

const val EPUB_EXTENSION = "epub"
const val EPUB_MIME_TYPE = "application/epub+zip"

private const val EPUB_MIMETYPE_ENTRY = "mimetype"
private const val EPUB_CONTAINER_ENTRY = "META-INF/container.xml"

// Local-file-header signature "PK\u0003\u0004".
private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

/** True if the path or file name ends in `.epub` (case-insensitive). */
fun isEpubFile(pathOrName: String): Boolean =
  pathOrName.endsWith(".$EPUB_EXTENSION", ignoreCase = true)

fun isEpubFile(file: File): Boolean = isEpubFile(file.name)

/**
 * Content check: a ZIP (PK magic) containing a `mimetype` entry equal to
 * [EPUB_MIME_TYPE], or failing that a `META-INF/container.xml` entry.
 * Never throws; unreadable or non-ZIP input is simply invalid.
 */
fun isValidEpubFile(file: File): Boolean =
  try {
    file.isFile && file.inputStream().buffered().use(::isValidEpubStream)
  } catch (_: IOException) {
    false
  } catch (_: SecurityException) {
    false
  }

fun isValidEpubStream(inputStream: InputStream): Boolean {
  val stream = if (inputStream.markSupported()) inputStream else inputStream.buffered()
  stream.mark(ZIP_MAGIC.size)
  val header = ByteArray(ZIP_MAGIC.size)
  val hasMagic = stream.readUpTo(header) == header.size && header.contentEquals(ZIP_MAGIC)
  stream.reset()
  if (!hasMagic) return false
  return try {
    hasEpubEntry(ZipInputStream(stream))
  } catch (_: IOException) {
    false
  }
}

private fun hasEpubEntry(zip: ZipInputStream): Boolean {
  var hasContainer = false
  while (true) {
    val name = zip.nextEntry?.name ?: break
    if (name == EPUB_MIMETYPE_ENTRY && zip.readMimeType() == EPUB_MIME_TYPE) return true
    if (name == EPUB_CONTAINER_ENTRY) hasContainer = true
  }
  return hasContainer
}

private fun InputStream.readMimeType(): String {
  val buf = ByteArray(EPUB_MIME_TYPE.length + 1)
  return String(buf, 0, readUpTo(buf), Charsets.US_ASCII).trim()
}

private fun InputStream.readUpTo(buf: ByteArray): Int {
  var total = 0
  while (total < buf.size) {
    val n = read(buf, total, buf.size - total)
    if (n < 0) break
    total += n
  }
  return total
}
