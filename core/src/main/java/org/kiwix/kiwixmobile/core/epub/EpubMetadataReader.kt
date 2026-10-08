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

import android.graphics.Bitmap
import android.util.Size
import org.kiwix.kiwixmobile.core.epub.reader.EpubPublicationOpener
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.coverFitting
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What the library shows about a book. */
class EpubBookInfo(
  val identifier: String?,
  val title: String,
  val authors: List<String>,
  val language: String?,
  val cover: ByteArray?
)

fun interface EpubMetadataReader {
  /** Null if [file] can't be parsed as an EPUB. */
  suspend fun read(file: File): EpubBookInfo?
}

/** Reads library metadata and cover through Readium, the same parser the reader uses. */
@Singleton
class ReadiumEpubMetadataReader @Inject constructor(
  private val opener: EpubPublicationOpener
) : EpubMetadataReader {
  override suspend fun read(file: File): EpubBookInfo? {
    val publication = opener.open(file).getOrNull() ?: return null
    return try {
      publication.toBookInfo()
    } finally {
      publication.close()
    }
  }

  private suspend fun Publication.toBookInfo() = EpubBookInfo(
    identifier = metadata.identifier,
    title = metadata.title.orEmpty(),
    authors = metadata.authors.map { it.name },
    language = metadata.languages.firstOrNull(),
    cover = runCatching { coverFitting(Size(COVER_SIDE, COVER_SIDE)) }.getOrNull()?.toJpeg()
  )

  private fun Bitmap.toJpeg(): ByteArray = ByteArrayOutputStream().use {
    compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
    recycle()
    it.toByteArray()
  }

  private companion object {
    const val COVER_SIDE = 256
    const val JPEG_QUALITY = 85
  }
}
