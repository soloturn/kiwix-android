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

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.kiwix.kiwixmobile.core.utils.datastore.KiwixDataStore
import org.kiwix.kiwixmobile.core.utils.files.Log
import org.kiwix.kiwixmobile.core.utils.files.saf.DocumentTree
import org.kiwix.kiwixmobile.core.utils.files.saf.DocumentTree.Companion.PARTIAL_SUFFIX
import org.kiwix.kiwixmobile.core.utils.files.saf.LibraryFolder
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides where a download is written, and turns a finished download into a book location.
 *
 * - SAF library folder: a `<name>.part` document, renamed when complete.
 * - Default `Documents/Kiwix` without direct write access: a pending MediaStore row, published
 *   when complete. Needs no permission, so it is what Play builds use.
 * - Anything else: a plain file path, as before.
 *
 * MediaStore deletes pending rows that are left untouched for about a week, so a download that
 * stays paused that long starts over.
 */
@Singleton
class DownloadTargets @Inject constructor(
  @param:ApplicationContext private val context: Context,
  private val kiwixDataStore: KiwixDataStore,
  private val libraryFolder: LibraryFolder
) {
  private val resolver: ContentResolver get() = context.contentResolver

  /** Path or content:// URI Fetch should write [fileName] to. */
  suspend fun create(fileName: String): String {
    val storage = File(kiwixDataStore.selectedStorage.first())
    val inTree = libraryFolder.activeTreeUri()?.let { treeTarget(it, fileName) }
    val viaMediaStore = inTree == null &&
      libraryFolder.isPublicDocuments(storage) &&
      usesMediaStore()
    val contentTarget = inTree ?: if (viaMediaStore) mediaStoreTarget(fileName) else null
    return contentTarget?.let { "$it" } ?: "$storage/${LibraryFolder.KIWIX_DIRECTORY}/$fileName"
  }

  /**
   * Publishes a finished download and returns where the book can be read from: a file path
   * when we may open it directly, otherwise its content:// URI. Null if it vanished.
   */
  fun finish(target: String): String? =
    when {
      !ContentUriStorageResolver.isContentUri(target) -> target
      DocumentTree.isDocumentInTree(target.toUri()) -> finishTreeDocument(target.toUri())
      else -> finishMediaStoreRow(target.toUri())
    }

  /**
   * Whether the Documents default is written through MediaStore. Direct paths work with All
   * Files Access, or on Android 10 non-Play builds under legacy storage with the write grant.
   */
  suspend fun usesMediaStore(): Boolean =
    when {
      Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> false
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> !Environment.isExternalStorageManager()
      else -> kiwixDataStore.isPlayStoreBuild.first() || !hasWritePermission()
    }

  private fun hasWritePermission() =
    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
      PackageManager.PERMISSION_GRANTED

  private fun treeTarget(tree: Uri, fileName: String): Uri? {
    val documentTree = DocumentTree(resolver)
    val partName = "$fileName$PARTIAL_SUFFIX"
    // Reuse a partial file from an earlier attempt so a re-enqueued download resumes into it.
    return documentTree.findChild(tree, documentTree.rootDocumentId(tree), partName)?.uri
      ?: documentTree.createDocument(tree, partName)
  }

  private fun finishTreeDocument(document: Uri): String? {
    val documentTree = DocumentTree(resolver)
    val name = documentTree.stat(document)?.name ?: return null
    val finished = if (name.endsWith(PARTIAL_SUFFIX)) {
      documentTree.rename(document, name.removeSuffix(PARTIAL_SUFFIX))
    } else {
      document
    }
    return finished?.let { "$it" }
  }

  private fun mediaStoreTarget(fileName: String): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      runCatching { findPendingRow(fileName) ?: insertPendingRow(fileName) }
        .onFailure { Log.e(TAG, "MediaStore insert failed for $fileName: $it") }
        .getOrNull()
    } else {
      null
    }

  @RequiresApi(Build.VERSION_CODES.Q)
  private fun insertPendingRow(fileName: String): Uri? =
    resolver.insert(
      mediaStoreCollection(),
      ContentValues().apply {
        put(MediaColumns.DISPLAY_NAME, fileName)
        put(MediaColumns.MIME_TYPE, DocumentTree.ZIM_MIME_TYPE)
        put(MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
        put(MediaColumns.IS_PENDING, 1)
      }
    )

  @RequiresApi(Build.VERSION_CODES.Q)
  private fun findPendingRow(fileName: String): Uri? {
    val selection = "${MediaColumns.DISPLAY_NAME}=? AND ${MediaColumns.RELATIVE_PATH}=?"
    val args = arrayOf(fileName, RELATIVE_PATH)
    val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      resolver.query(
        mediaStoreCollection(),
        arrayOf(MediaColumns._ID),
        Bundle().apply {
          putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
          putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
          putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        },
        null
      )
    } else {
      @Suppress("DEPRECATION")
      resolver.query(
        MediaStore.setIncludePending(mediaStoreCollection()),
        arrayOf(MediaColumns._ID),
        selection,
        args,
        null
      )
    }
    return cursor?.use {
      if (it.moveToFirst()) Uri.withAppendedPath(mediaStoreCollection(), "${it.getLong(0)}") else null
    }
  }

  private fun finishMediaStoreRow(row: Uri): String? =
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        resolver.update(row, ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }, null, null)
      }
      // Our own MediaStore files are readable by path; that avoids holding a descriptor.
      @Suppress("DEPRECATION")
      val path = resolver.query(row, arrayOf(MediaColumns.DATA), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
      path?.takeIf { File(it).canRead() } ?: "$row"
    }.onFailure { Log.e(TAG, "Could not publish $row: $it") }.getOrNull()

  @RequiresApi(Build.VERSION_CODES.Q)
  private fun mediaStoreCollection(): Uri =
    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

  companion object {
    private const val TAG = "DownloadTargets"
    val RELATIVE_PATH = "${Environment.DIRECTORY_DOCUMENTS}/${LibraryFolder.KIWIX_DIRECTORY}/"
  }
}
