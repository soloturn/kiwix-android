/*
 * Kiwix Android
 * Copyright (c) 2026 Kiwix <android.kiwix.org>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.kiwix.kiwixmobile.nav.destination.library.online.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kiwix.kiwixmobile.core.entity.LibkiwixBook
import org.kiwix.kiwixmobile.zimManager.Fat32Checker.FileSystemState.DetectingFileSystem
import org.kiwix.kiwixmobile.zimManager.libraryView.LibraryListItem
import org.kiwix.kiwixmobile.zimManager.libraryView.LibraryListItem.BookItem
import org.kiwix.kiwixmobile.zimManager.libraryView.LibraryListItem.LibraryDownloadItem

internal class PendingHomeBookDownloadProgress(
  private val scope: CoroutineScope,
  private val onPendingBookChanged: (LibkiwixBook?) -> Unit
) {
  private var queuedBook: BookItem? = null
  private var pendingBookId: String? = null
  private var timeout: Job? = null

  fun queue(book: LibkiwixBook) {
    queuedBook = BookItem(book, DetectingFileSystem)
  }

  fun startQueued(): BookItem? {
    val item = queuedBook.also { queuedBook = null } ?: return null
    pendingBookId = item.book.id
    onPendingBookChanged(item.book)
    timeout?.cancel()
    timeout = scope.launch {
      delay(PENDING_DOWNLOAD_TIMEOUT_MILLIS)
      clear(item.book.id)
    }
    return item
  }

  fun onLibraryItemsUpdated(items: List<LibraryListItem>) {
    pendingBookId?.let { bookId ->
      if (items.any { it is LibraryDownloadItem && it.id == bookId.hashCode().toLong() }) {
        clear(bookId)
      }
    }
  }

  fun clear(bookId: String) {
    if (pendingBookId == bookId) {
      pendingBookId = null
      timeout?.cancel()
      timeout = null
      onPendingBookChanged(null)
    }
  }

  fun cancel() {
    timeout?.cancel()
  }

  private companion object {
    const val PENDING_DOWNLOAD_TIMEOUT_MILLIS = 30_000L
  }
}
