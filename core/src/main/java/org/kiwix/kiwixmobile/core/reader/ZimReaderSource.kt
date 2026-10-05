/*
 * Kiwix Android
 * Copyright (c) 2024 Kiwix <android.kiwix.org>
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.kiwix.kiwixmobile.core.CoreApp
import org.kiwix.kiwixmobile.core.extensions.canReadFile
import org.kiwix.kiwixmobile.core.extensions.isFileExist
import org.kiwix.kiwixmobile.core.utils.ZERO
import org.kiwix.kiwixmobile.core.utils.files.FileUtils.getAssetFileDescriptorFromUri
import org.kiwix.kiwixmobile.core.utils.files.FileUtils.isFileDescriptorCanOpenWithLibkiwix
import org.kiwix.kiwixmobile.core.utils.files.saf.DocumentTree
import org.kiwix.libzim.Archive
import org.kiwix.libzim.FdInput
import java.io.File
import java.io.Serializable

/**
 * Where a ZIM is read from: a file path, a `content://` URI, or descriptors handed over directly
 * (branded apps' bundled assets).
 *
 * URI sources open their descriptors only when an archive is actually needed. The library can
 * hold many URI-backed books and building a source for each one must not consume a descriptor
 * per book, or the process fd limit runs out.
 */
class ZimReaderSource(
  val file: File? = null,
  val uri: Uri? = null,
  assetFileDescriptorList: List<AssetFileDescriptor>? = null
) : Serializable {
  constructor(uri: Uri) : this(uri = uri, assetFileDescriptorList = null)

  constructor(file: File) : this(file = file, uri = null)

  @Transient
  private val providedDescriptors: List<AssetFileDescriptor>? = assetFileDescriptorList

  @Transient
  private var openedDescriptors: List<AssetFileDescriptor>? = null

  /** Descriptors for reading; for URI sources this opens them on first access. */
  val assetFileDescriptorList: List<AssetFileDescriptor>?
    get() = providedDescriptors ?: uri?.let { openDescriptors(it) }

  @Synchronized
  private fun openDescriptors(uri: Uri): List<AssetFileDescriptor>? =
    openedDescriptors ?: descriptorOpener(uri)?.also { openedDescriptors = it }

  companion object {
    /** Opens every part of the ZIM behind [uri]; split ZIMs in a SAF tree yield several parts. */
    @Volatile
    var descriptorOpener: (Uri) -> List<AssetFileDescriptor>? = ::openUriDescriptors

    private fun openUriDescriptors(uri: Uri): List<AssetFileDescriptor>? {
      val context = CoreApp.instance
      val parts = DocumentTree(context.contentResolver).splitZimParts(uri)
      val descriptors = parts.map { part ->
        getAssetFileDescriptorFromUri(context, part)?.firstOrNull()
      }
      return if (descriptors.isNotEmpty() && descriptors.all { it != null }) {
        descriptors.filterNotNull()
      } else {
        descriptors.forEach { runCatching { it?.close() } }
        null
      }
    }

    fun fromDatabaseValue(databaseValue: String?) =
      databaseValue?.run {
        if (startsWith("content://")) {
          ZimReaderSource(toUri())
        } else {
          ZimReaderSource(File(this))
        }
      }
  }

  suspend fun exists(ioDispatcher: CoroutineDispatcher): Boolean = withContext(ioDispatcher) {
    when {
      file != null -> file.isFileExist(ioDispatcher)
      providedDescriptors?.isNotEmpty() == true ->
        providedDescriptors.first().parcelFileDescriptor.fileDescriptor.valid()

      uri != null -> uriExists(uri)
      else -> false
    }
  }

  /** Cheap existence check that does not keep a descriptor open. */
  private fun uriExists(uri: Uri): Boolean {
    openedDescriptors?.let { return it.first().parcelFileDescriptor.fileDescriptor.valid() }
    val resolver = CoreApp.instance.contentResolver
    return runCatching {
      resolver.query(uri, null, null, null, null)?.use { it.moveToFirst() }
        ?: resolver.openAssetFileDescriptor(uri, "r")?.use { true }
        ?: false
    }.getOrDefault(false)
  }

  suspend fun canOpenInLibkiwix(ioDispatcher: CoroutineDispatcher): Boolean =
    withContext(ioDispatcher) {
      when {
        file?.canReadFile(ioDispatcher) == true -> true
        assetFileDescriptorList?.isNotEmpty() == true &&
          assetFileDescriptorList?.first()?.parcelFileDescriptor?.fileDescriptor
            ?.let(::isFileDescriptorCanOpenWithLibkiwix) == true -> true

        else -> false
      }
    }

  suspend fun createArchive(ioDispatcher: CoroutineDispatcher): Archive? =
    withContext(ioDispatcher) {
      if (canOpenInLibkiwix(ioDispatcher)) {
        val descriptors = assetFileDescriptorList
        when {
          file != null -> Archive(file.canonicalPath)
          descriptors?.isNotEmpty() == true -> {
            val fdInputArray = getFdInputArrayFromAssetFileDescriptorList(descriptors)
            if (fdInputArray.size == 1) {
              Archive(fdInputArray[0])
            } else {
              Archive(fdInputArray)
            }
          }

          else -> null
        }
      } else {
        null
      }
    }

  private fun getFdInputArrayFromAssetFileDescriptorList(
    assetFileDescriptorList: List<AssetFileDescriptor>
  ): Array<FdInput> =
    assetFileDescriptorList.map {
      FdInput(
        it.parcelFileDescriptor.fileDescriptor,
        it.startOffset,
        it.length
      )
    }.toTypedArray()

  /** Closes descriptors this source opened itself; a later read reopens them. */
  @Synchronized
  fun releaseDescriptors() {
    openedDescriptors?.forEach { runCatching { it.close() } }
    openedDescriptors = null
  }

  fun toDatabase(): String = file?.canonicalPath ?: "$uri"

  /**
   * Compares two sources for equality based on the underlying file, URI,
   * or descriptor list.
   */
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is ZimReaderSource) return false
    val ownDescriptors = providedDescriptors
    val otherDescriptors = other.providedDescriptors
    return when {
      file != null && other.file != null ->
        file.canonicalPath == other.file.canonicalPath

      uri != null && other.uri != null -> uri == other.uri

      !ownDescriptors.isNullOrEmpty() && !otherDescriptors.isNullOrEmpty() ->
        ownDescriptors.size == otherDescriptors.size &&
          ownDescriptors.zip(otherDescriptors).all { (a, b) ->
            a.startOffset == b.startOffset && a.length == b.length
          }

      else -> false
    }
  }

  fun getUri(activity: AppCompatActivity): Uri? = when {
    file != null -> {
      FileProvider.getUriForFile(
        activity,
        "${activity.packageName}.fileprovider",
        file
      )
    }

    else -> uri
  }

  override fun hashCode(): Int = when {
    file != null -> file.canonicalPath.hashCode()
    uri != null -> uri.hashCode()
    !providedDescriptors.isNullOrEmpty() ->
      providedDescriptors.sumOf { it.startOffset.hashCode() + it.length.hashCode() }

    else -> ZERO
  }
}
