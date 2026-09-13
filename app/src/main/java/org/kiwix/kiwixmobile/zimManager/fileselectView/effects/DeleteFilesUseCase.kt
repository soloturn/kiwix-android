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

package org.kiwix.kiwixmobile.zimManager.fileselectView.effects

import kotlinx.coroutines.CoroutineDispatcher
import org.kiwix.kiwixmobile.core.dao.LibkiwixBookOnDisk
import org.kiwix.kiwixmobile.core.di.IoDispatcher
import org.kiwix.kiwixmobile.core.extensions.isFileExist
import org.kiwix.kiwixmobile.core.main.reader.helper.ReaderWebViewManager
import org.kiwix.kiwixmobile.core.reader.ZimReaderContainer
import org.kiwix.kiwixmobile.core.utils.files.FileUtils
import org.kiwix.kiwixmobile.core.zim_manager.fileselect_view.BooksOnDiskListItem
import javax.inject.Inject

data class DeleteFilesUseCase @Inject constructor(
  private val libkiwixBookOnDisk: LibkiwixBookOnDisk,
  private val readerWebViewManager: ReaderWebViewManager,
  private val zimReaderContainer: ZimReaderContainer,
  @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
  suspend operator fun invoke(
    books: List<BooksOnDiskListItem.BookOnDisk>
  ): Boolean {
    var readerWebViewsDestroyed = false
    return books.fold(true) { acc, book ->
      if (!acc) {
        false
      } else {
        val currentSourceForThisBook = zimReaderContainer.zimReaderSource
        val isCurrentBook = book.zimReaderSource == currentSourceForThisBook
        if (
          isCurrentBook &&
          hasDeletionTarget(book) &&
          !readerWebViewsDestroyed
        ) {
          // Stop all WebViews first so Chromium workers no longer issue requests against
          // the soon-to-be-disposed archive.
          readerWebViewManager.destroyAllTabs()
          readerWebViewsDestroyed = true
        }
        val deleted = deleteBook(book)
        if (deleted && isCurrentBook && book.zimReaderSource == currentSourceForThisBook) {
          zimReaderContainer.setZimReaderSource(null)
        }
        deleted
      }
    }
  }

  @Suppress("ReturnCount")
  private suspend fun deleteBook(
    book: BooksOnDiskListItem.BookOnDisk
  ): Boolean {
    if (!hasDeletionTarget(book)) return false
    val file = book.zimReaderSource.file ?: return false

    FileUtils.deleteZimFile(file.path, ioDispatcher)

    if (file.isFileExist(ioDispatcher)) {
      return false
    }

    libkiwixBookOnDisk.delete(book.book.id)
    return true
  }

  private fun hasDeletionTarget(book: BooksOnDiskListItem.BookOnDisk): Boolean =
    book.zimReaderSource.file != null
}
