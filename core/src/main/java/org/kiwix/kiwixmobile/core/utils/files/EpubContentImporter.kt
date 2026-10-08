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
import org.kiwix.kiwixmobile.core.utils.TAG_KIWIX
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

const val EPUB_IMPORT_DIR = "epub"
const val EPUB_MAX_IMPORT_BYTES = 512L * 1024 * 1024
private const val UNKNOWN_SIZE = -1L
private const val FALLBACK_NAME = "book"
private const val PART_SUFFIX = ".part"
private const val FREE_SPACE_MARGIN = 64L * 1024 * 1024
private const val STALE_PART_AGE_MS = 60L * 60 * 1000
private const val HASH_LENGTH = 16
private const val COPY_BUFFER_BYTES = 64 * 1024

sealed interface EpubImportResult {
  data class Imported(val file: File) : EpubImportResult
  data class TooLarge(val size: Long, val max: Long) : EpubImportResult
  data class NotEnoughSpace(val required: Long, val available: Long) : EpubImportResult

  /** Missing or unreadable stream, or content that is not a valid EPUB. */
  data object Invalid : EpubImportResult
}

/**
 * Copies a `content://` EPUB into app-private storage so it can be opened as a [File].
 * Blocking; call off the main thread (see [importEpubContentUri]).
 */
object EpubContentImporter {
  /**
   * Stores the stream as `<name>-<bytes>-<content hash>.epub`, so different books never share a
   * file and the same book from any provider (whatever its name) is stored once. Known-size
   * imports of an already stored book skip the copy.
   */
  @Suppress("ReturnCount", "LongParameterList")
  fun import(
    destDir: File,
    displayName: String?,
    size: Long,
    maxBytes: Long = EPUB_MAX_IMPORT_BYTES,
    freeSpace: (File) -> Long = File::getUsableSpace,
    openStream: () -> InputStream?
  ): EpubImportResult {
    if (size > maxBytes) return EpubImportResult.TooLarge(size, maxBytes)
    if (!destDir.isDirectory && !destDir.mkdirs()) return EpubImportResult.Invalid
    deleteStaleParts(destDir, STALE_PART_AGE_MS)
    val base = sanitizedBase(displayName)
    if (size != UNKNOWN_SIZE) {
      findStored(destDir, base, size)?.let {
        return EpubImportResult.Imported(it)
      }
    }
    val required = maxOf(size, 0L) + FREE_SPACE_MARGIN
    val available = freeSpace(destDir)
    if (available < required) return EpubImportResult.NotEnoughSpace(required, available)
    val part = runCatching { File.createTempFile("import-", PART_SUFFIX, destDir) }
      .getOrNull() ?: return EpubImportResult.Invalid
    return try {
      copyAndStore(part, destDir, base, maxBytes, openStream)
    } catch (_: IOException) {
      EpubImportResult.Invalid
    } catch (_: SecurityException) {
      EpubImportResult.Invalid
    } finally {
      part.delete()
    }
  }

  @Suppress("ReturnCount")
  private fun copyAndStore(
    part: File,
    destDir: File,
    base: String,
    maxBytes: Long,
    openStream: () -> InputStream?
  ): EpubImportResult {
    val digest = MessageDigest.getInstance("SHA-256")
    val copied = openStream()?.use { input ->
      part.outputStream().use { copyHashing(input, it, digest, maxBytes) }
    } ?: return EpubImportResult.Invalid
    if (copied > maxBytes) return EpubImportResult.TooLarge(copied, maxBytes)
    if (!isValidEpubFile(part)) return EpubImportResult.Invalid
    val hash = digest.digest().joinToString("") { "%02x".format(it) }.take(HASH_LENGTH)
    val existing = destDir.listFiles { _, name -> name.endsWith("-$hash.$EPUB_EXTENSION") }
      ?.firstOrNull(::isValidEpubFile)
    if (existing != null) return EpubImportResult.Imported(existing)
    val target = File(destDir, "$base-$copied-$hash.$EPUB_EXTENSION")
    return if (part.renameTo(target)) EpubImportResult.Imported(target) else EpubImportResult.Invalid
  }

  /** Returns the bytes copied, or a value above [maxBytes] as soon as the cap is crossed. */
  private fun copyHashing(
    input: InputStream,
    out: OutputStream,
    digest: MessageDigest,
    maxBytes: Long
  ): Long {
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var total = 0L
    while (true) {
      val read = input.read(buffer)
      if (read < 0) return total
      total += read
      if (total > maxBytes) return total
      digest.update(buffer, 0, read)
      out.write(buffer, 0, read)
    }
  }

  private fun findStored(destDir: File, base: String, size: Long): File? {
    val pattern = Regex("${Regex.escape(base)}-$size-[0-9a-f]{$HASH_LENGTH}\\.$EPUB_EXTENSION")
    return destDir.listFiles { _, name -> pattern.matches(name) }?.firstOrNull(::isValidEpubFile)
  }

  /** Removes `.part` files left by an interrupted copy; younger ones may be in progress. */
  fun deleteStaleParts(destDir: File, olderThanMs: Long) {
    val cutoff = System.currentTimeMillis() - olderThanMs
    destDir.listFiles { _, name -> name.endsWith(PART_SUFFIX) }
      ?.filter { it.lastModified() <= cutoff }
      ?.forEach { it.delete() }
  }

  /** The display name without path, extension and unsafe characters. */
  fun sanitizedBase(displayName: String?): String = displayName.orEmpty()
    .substringAfterLast('/')
    .replace(Regex("\\.$EPUB_EXTENSION$", RegexOption.IGNORE_CASE), "")
    .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
    .trim('.', '_')
    .take(MAX_BASE_LENGTH)
    .ifEmpty { FALLBACK_NAME }

  private const val MAX_BASE_LENGTH = 100
}

/** Imports [uri] into `filesDir/epub` on the IO dispatcher; null on any failure (logged). */
suspend fun importEpubContentUri(context: Context, uri: Uri): File? =
  when (val result = importEpubContentUriResult(context, uri)) {
    is EpubImportResult.Imported -> result.file
    else -> {
      Log.w(TAG_KIWIX, "EPUB import failed: $result")
      null
    }
  }

/** Like [importEpubContentUri] but says why an import failed. */
suspend fun importEpubContentUriResult(context: Context, uri: Uri): EpubImportResult =
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

/** Clears `.part` files an earlier process left behind; call off the main thread at startup. */
fun deleteStaleEpubParts(context: Context) =
  EpubContentImporter.deleteStaleParts(File(context.filesDir, EPUB_IMPORT_DIR), 0L)

/** True if [file] is inside app-private storage and so needs no storage permission. */
fun isAppPrivateFile(context: Context, file: File): Boolean {
  val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
  val roots = listOfNotNull(context.filesDir, context.cacheDir, context.getExternalFilesDir(null))
  return roots.any { path.startsWith(runCatching { it.canonicalPath }.getOrDefault(it.path) + File.separator) }
}
