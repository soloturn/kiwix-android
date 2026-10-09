/*
 * Kiwix Android
 * Copyright (c) 2019 Kiwix <android.kiwix.org>
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

package org.kiwix.kiwixmobile.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kiwix.kiwixmobile.core.dao.DownloadRoomDao
import org.kiwix.kiwixmobile.core.dao.LibkiwixBookmarks
import org.kiwix.kiwixmobile.core.di.IoDispatcher
import org.kiwix.kiwixmobile.core.downloader.model.DownloadModel
import org.kiwix.kiwixmobile.core.epub.EpubLibraryManager
import org.kiwix.kiwixmobile.core.reader.ZimFileReader
import org.kiwix.kiwixmobile.core.reader.ZimReaderSource
import org.kiwix.kiwixmobile.core.utils.files.FileSearch
import org.kiwix.kiwixmobile.core.utils.files.ScanningProgressListener
import org.kiwix.kiwixmobile.core.utils.files.isEpubFile
import org.kiwix.libkiwix.Book
import java.io.File
import javax.inject.Inject

@Suppress("LongParameterList")
class StorageObserver @Inject constructor(
  private val downloadRoomDao: DownloadRoomDao,
  private val fileSearch: FileSearch,
  private val zimReaderFactory: ZimFileReader.Factory,
  private val libkiwixBookmarks: LibkiwixBookmarks,
  private val libkiwixBookFactory: LibkiwixBookFactory,
  private val epubLibraryManager: EpubLibraryManager,
  @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
  private val importScope = CoroutineScope(SupervisorJob() + ioDispatcher)
  private val importMutex = Mutex()

  fun getBooksOnFileSystem(
    scanningProgressListener: ScanningProgressListener
  ): Flow<List<Book>> = flow {
    val (epubs, zims) = scanFiles(scanningProgressListener).first()
      .partition { isEpubFile(it.absolutePath) }
    // Start before emit: callers take first(), so nothing after emit would ever run.
    importEpubsInBackground(epubs)
    val downloads = downloadRoomDao.downloads().first()
    val result = toFilesThatAreNotDownloading(zims, downloads)
      .mapNotNull { convertToLibkiwixBook(it) }
    emit(result)
  }.flowOn(ioDispatcher)

  // EPUBs go to their own library table (observed via Room), so the ZIM list must not wait for the
  // slow parse. Own scope: callers take `first()`, which would cancel work launched in the flow.
  private fun importEpubsInBackground(epubs: List<File>) {
    if (epubs.isEmpty()) return
    importScope.launch {
      importMutex.withLock { runCatching { epubLibraryManager.importScanned(epubs) } }
    }
  }

  private fun scanFiles(scanningProgressListener: ScanningProgressListener): Flow<List<File>> =
    fileSearch.scan(scanningProgressListener, includeEpub = true)

  private fun toFilesThatAreNotDownloading(files: List<File>, downloads: List<DownloadModel>) =
    files.filter { fileHasNoMatchingDownload(downloads, it) }

  private fun fileHasNoMatchingDownload(downloads: List<DownloadModel>, file: File) =
    downloads.none { file.absolutePath.endsWith(it.fileNameFromUrl) }

  private suspend fun convertToLibkiwixBook(file: File) =
    zimReaderFactory.create(ZimReaderSource(file), false)
      ?.let { zimFileReader ->
        libkiwixBookFactory.create().apply {
          update(zimFileReader.jniKiwixReader)
        }.also {
          // add the book to libkiwix library to validate the imported bookmarks
          libkiwixBookmarks.addBookToLibrary(archive = zimFileReader.jniKiwixReader)
          zimFileReader.dispose()
        }
      }
}

interface LibkiwixBookFactory {
  fun create(): Book
}
