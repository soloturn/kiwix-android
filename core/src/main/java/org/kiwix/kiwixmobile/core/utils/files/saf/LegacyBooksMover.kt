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

package org.kiwix.kiwixmobile.core.utils.files.saf

import android.content.Context
import android.os.Environment
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.kiwix.kiwixmobile.core.dao.HistoryRoomDao
import org.kiwix.kiwixmobile.core.dao.LibkiwixBookOnDisk
import org.kiwix.kiwixmobile.core.dao.NotesRoomDao
import org.kiwix.kiwixmobile.core.di.IoDispatcher
import org.kiwix.kiwixmobile.core.downloader.downloadManager.ContentUriStorageResolver
import org.kiwix.kiwixmobile.core.downloader.downloadManager.DownloadTargets
import org.kiwix.kiwixmobile.core.utils.datastore.KiwixDataStore
import org.kiwix.kiwixmobile.core.utils.files.FileUtils
import org.kiwix.kiwixmobile.core.utils.files.Log
import org.kiwix.kiwixmobile.core.zim_manager.fileselect_view.BooksOnDiskListItem.BookOnDisk
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves books that older versions kept in app-specific storage (deleted on uninstall) into the
 * library folder: the picked SAF folder if there is one, else Documents/Kiwix. Only runs when
 * the user accepts, since it can copy many gigabytes.
 */
@Suppress("LongParameterList")
@Singleton
class LegacyBooksMover @Inject constructor(
  @param:ApplicationContext private val context: Context,
  private val kiwixDataStore: KiwixDataStore,
  private val libraryFolder: LibraryFolder,
  private val libkiwixBookOnDisk: LibkiwixBookOnDisk,
  private val downloadTargets: DownloadTargets,
  private val historyRoomDao: HistoryRoomDao,
  private val notesRoomDao: NotesRoomDao,
  @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
  data class Offer(val books: List<BookOnDisk>, val totalBytes: Long, val destination: String)

  data class Result(val moved: Int, val failed: Int)

  /** Books worth offering to move, or null when there are none or nowhere safer to put them. */
  suspend fun offer(): Offer? = withContext(ioDispatcher) {
    if (kiwixDataStore.legacyBooksMoveDeclined.first()) return@withContext null
    val destination = destinationName() ?: return@withContext null
    val books = libkiwixBookOnDisk.getBooks().filter { book ->
      book.zimReaderSource.file?.let { it.isFile && isInAppSpecificStorage(it) } == true
    }
    books.takeIf(List<BookOnDisk>::isNotEmpty)?.let {
      Offer(it, it.sumOf { book -> parts(book.zimReaderSource.file!!).sumOf(File::length) }, destination)
    }
  }

  suspend fun decline() = kiwixDataStore.setLegacyBooksMoveDeclined(true)

  suspend fun move(offer: Offer, onProgress: suspend (Int, Int) -> Unit = { _, _ -> }): Result =
    withContext(ioDispatcher) {
      if (libraryFolder.activeTreeUri() == null) {
        // Future downloads go to the new place as well.
        kiwixDataStore.setSelectedStorage(kiwixDataStore.publicDocumentsDirectory().path)
      }
      var moved = 0
      offer.books.forEachIndexed { index, book ->
        onProgress(index + 1, offer.books.size)
        runCatching { moveBook(book) }
          .onSuccess { moved++ }
          .onFailure { Log.e(TAG, "Could not move ${book.book.title}: $it") }
      }
      Result(moved, offer.books.size - moved)
    }

  private suspend fun moveBook(book: BookOnDisk) {
    val source = book.zimReaderSource.file ?: throw IOException("Not a file book")
    val sourceParts = parts(source)
    val locations = sourceParts.map { copyToLibrary(it) }
    val newLocation = locations.first()
    libkiwixBookOnDisk.delete(book.book.id)
    libkiwixBookOnDisk.insertLocation(newLocation)
    val oldLocation = source.canonicalPath
    historyRoomDao.relocateBook(oldLocation, newLocation)
    notesRoomDao.relocateBook(oldLocation, newLocation)
    if (kiwixDataStore.currentZimFile.first() == oldLocation) {
      kiwixDataStore.setCurrentZimFile(newLocation)
    }
    // Only delete once the copy is registered, so an interrupted move loses nothing.
    sourceParts.forEach(File::delete)
  }

  /** Copies one file into the library folder and returns where it can be read from. */
  private suspend fun copyToLibrary(part: File): String {
    val target = downloadTargets.create(part.name)
    val output = if (ContentUriStorageResolver.isContentUri(target)) {
      context.contentResolver.openOutputStream(target.toUri(), "wt")
    } else {
      File(target).also { it.parentFile?.mkdirs() }.outputStream()
    }
    val copied = output?.use { stream -> part.inputStream().use { it.copyTo(stream) } }
    if (copied != part.length()) throw IOException("Copied $copied of ${part.length()} bytes")
    return downloadTargets.finish(target) ?: throw IOException("Cannot publish $target")
  }

  private fun parts(file: File): List<File> =
    if (FileUtils.isSplittedZimFile(file.name)) {
      val prefix = file.name.dropLast(SPLIT_SUFFIX_LENGTH)
      file.parentFile?.listFiles { sibling ->
        sibling.name.startsWith(prefix) && sibling.name.length == file.name.length
      }?.sortedBy(File::getName).orEmpty().ifEmpty { listOf(file) }
    } else {
      listOf(file)
    }

  private fun isInAppSpecificStorage(file: File): Boolean {
    val path = runCatching { file.canonicalPath }.getOrDefault(file.path)
    return appSpecificRoots().any { root -> path.startsWith(root) }
  }

  private fun appSpecificRoots(): List<String> =
    listOf(
      *context.externalMediaDirs,
      *context.getExternalFilesDirs(null),
      context.filesDir
    ).filterNotNull()
      .mapNotNull { runCatching { it.canonicalPath }.getOrNull() }

  private suspend fun destinationName(): String? =
    libraryFolder.activeTreeUri()?.let(libraryFolder::describe)
      ?: if (libraryFolder.isPublicDocuments(File(kiwixDataStore.defaultLibraryStorage()))) {
        "${Environment.DIRECTORY_DOCUMENTS}/${LibraryFolder.KIWIX_DIRECTORY}"
      } else {
        null
      }

  companion object {
    private const val TAG = "LegacyBooksMover"
    private const val SPLIT_SUFFIX_LENGTH = 2
  }
}
