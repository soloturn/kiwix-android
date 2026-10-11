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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps small cover thumbnails for library entries under `filesDir/epub_covers`. */
@Singleton
class EpubCoverStore @Inject constructor(
  @param:ApplicationContext private val context: Context
) {
  private val dir get() = File(context.filesDir, COVER_DIR)

  /** Downscales [bytes] to a JPEG thumbnail; returns its absolute path, or null if undecodable. */
  fun save(id: String, bytes: ByteArray): String? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return null
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(longest) }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
    dir.mkdirs()
    File(dir, "${fileKey(id)}.jpg").also { out ->
      out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
      bitmap.recycle()
    }.absolutePath
  }.getOrNull()

  fun delete(coverPath: String?) {
    coverPath?.let { runCatching { File(it).delete() } }
  }

  companion object {
    private const val COVER_DIR = "epub_covers"
    private const val MAX_SIDE = 256
    private const val JPEG_QUALITY = 85

    internal fun sampleSizeFor(longestSide: Int): Int {
      var sample = 1
      while (longestSide / (sample * 2) >= MAX_SIDE) sample *= 2
      return sample
    }

    internal fun fileKey(id: String): String =
      MessageDigest.getInstance("SHA-1").digest(id.toByteArray())
        .joinToString("") { "%02x".format(it) }
  }
}
