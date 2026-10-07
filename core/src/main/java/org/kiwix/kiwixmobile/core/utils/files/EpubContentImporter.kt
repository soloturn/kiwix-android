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

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

const val EPUB_IMPORT_DIR = "epub"
private const val UNKNOWN_SIZE = -1L
private const val FALLBACK_NAME = "book"
private const val PART_SUFFIX = ".part"

/**
 * Copies a `content://` EPUB into app-private storage so it can be opened as a [File].
 * Blocking; call off the main thread (see [importEpubContentUri]).
 */
object EpubContentImporter {
  /**
   * Returns the private copy (reusing an existing valid one of the same name and size),
   * or null if the stream is missing, unreadable or not a valid EPUB.
   */
  @Suppress("ReturnCount")
  fun import(
    destDir: File,
    displayName: String?,
    size: Long,
    openStream: () -> InputStream?
  ): File? {
    val target = File(destDir, stableFileName(displayName, size))
    if (isReusable(target, size)) return target
    if (!destDir.isDirectory && !destDir.mkdirs()) return null
    val part = File(destDir, target.name + PART_SUFFIX)
    return try {
      val copied = openStream()?.use { input -> part.outputStream().use { input.copyTo(it) } }
      if (copied != null && isValidEpubFile(part) && part.renameTo(target)) target else null
    } catch (_: IOException) {
      null
    } catch (_: SecurityException) {
      null
    } finally {
      part.delete()
    }
  }

  private fun isReusable(target: File, size: Long): Boolean =
    size != UNKNOWN_SIZE && target.length() == size && isValidEpubFile(target)

  /** `<sanitized base>-<size>.epub`; the size distinguishes different books sharing a name. */
  fun stableFileName(displayName: String?, size: Long): String {
    val base = displayName.orEmpty()
      .substringAfterLast('/')
      .replace(Regex("\\.$EPUB_EXTENSION$", RegexOption.IGNORE_CASE), "")
      .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
      .trim('.', '_')
      .take(MAX_BASE_LENGTH)
      .ifEmpty { FALLBACK_NAME }
    val suffix = if (size == UNKNOWN_SIZE) "" else "-$size"
    return "$base$suffix.$EPUB_EXTENSION"
  }

  private const val MAX_BASE_LENGTH = 100
}

/** Imports [uri] into `filesDir/epub` on the IO dispatcher. */
suspend fun importEpubContentUri(context: Context, uri: Uri): File? =
  withContext(Dispatchers.IO) {
    var name: String? = null
    var size = UNKNOWN_SIZE
    runCatching {
      context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null
      )?.use {
        if (it.moveToFirst()) {
          name = it.getString(0)
          if (!it.isNull(1)) size = it.getLong(1)
        }
      }
    }
    EpubContentImporter.import(
      File(context.filesDir, EPUB_IMPORT_DIR),
      name ?: uri.lastPathSegment,
      size
    ) { context.contentResolver.openInputStream(uri) }
  }

/** True if [file] is inside app-private storage and so needs no storage permission. */
fun isAppPrivateFile(context: Context, file: File): Boolean {
  val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
  val roots = listOfNotNull(context.filesDir, context.cacheDir, context.getExternalFilesDir(null))
  return roots.any { path.startsWith(runCatching { it.canonicalPath }.getOrDefault(it.path) + File.separator) }
}
