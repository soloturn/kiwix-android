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

package org.kiwix.kiwixmobile.core.downloader.downloadManager

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.tonyodev.fetch2core.DefaultStorageResolver
import com.tonyodev.fetch2core.Downloader
import com.tonyodev.fetch2core.OutputResourceWrapper
import org.kiwix.kiwixmobile.core.utils.files.saf.DocumentTree
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * Lets Fetch write downloads to `content://` targets (SAF tree documents and MediaStore rows).
 *
 * Fetch 3.4.1 opens content URIs with mode "w", which truncates on most providers, so a resumed
 * download would keep only the bytes written after the resume offset. Opening with "rw" and
 * seeking keeps the already-downloaded prefix. Content targets are created before enqueueing,
 * so [createFile] never has to invent a document name.
 */
class ContentUriStorageResolver(
  context: Context,
  tempDir: String
) : DefaultStorageResolver(context, tempDir) {
  private val contentResolver: ContentResolver get() = context.contentResolver

  override fun createFile(file: String, increment: Boolean): String =
    if (isContentUri(file)) file else super.createFile(file, increment)

  override fun fileExists(file: String): Boolean =
    if (isContentUri(file)) {
      runCatching { contentResolver.openFileDescriptor(file.toUri(), "r")?.close() != null }
        .getOrDefault(false)
    } else {
      super.fileExists(file)
    }

  override fun preAllocateFile(file: String, contentLength: Long): Boolean =
    if (isContentUri(file)) true else super.preAllocateFile(file, contentLength)

  override fun deleteFile(file: String): Boolean =
    if (isContentUri(file)) deleteContent(file.toUri()) else super.deleteFile(file)

  override fun getRequestOutputResourceWrapper(
    request: Downloader.ServerRequest
  ): OutputResourceWrapper =
    if (isContentUri(request.file)) {
      openForResume(request.file.toUri())
    } else {
      super.getRequestOutputResourceWrapper(request)
    }

  fun openForResume(uri: Uri): OutputResourceWrapper {
    val descriptor = contentResolver.openFileDescriptor(uri, RESUMABLE_WRITE_MODE)
      ?: throw FileNotFoundException("Cannot open $uri for writing")
    return ParcelFileDescriptorOutput(descriptor)
  }

  private fun deleteContent(uri: Uri): Boolean = runCatching {
    if (DocumentTree.isDocumentInTree(uri)) {
      DocumentsContract.deleteDocument(contentResolver, uri)
    } else {
      contentResolver.delete(uri, null, null) > 0
    }
  }.getOrDefault(false)

  private class ParcelFileDescriptorOutput(
    private val descriptor: ParcelFileDescriptor
  ) : OutputResourceWrapper() {
    private val stream = FileOutputStream(descriptor.fileDescriptor)

    override fun write(byteArray: ByteArray, offSet: Int, length: Int) {
      stream.write(byteArray, offSet, length)
    }

    override fun setWriteOffset(offset: Long) {
      stream.channel.position(offset)
    }

    override fun flush() {
      stream.flush()
    }

    override fun close() {
      runCatching { stream.close() }
      descriptor.close()
    }
  }

  companion object {
    const val RESUMABLE_WRITE_MODE = "rw"

    fun isContentUri(file: String): Boolean = file.startsWith("${ContentResolver.SCHEME_CONTENT}://")
  }
}
