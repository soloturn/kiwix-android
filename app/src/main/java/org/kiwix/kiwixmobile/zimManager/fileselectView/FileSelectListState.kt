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
package org.kiwix.kiwixmobile.zimManager.fileselectView

import org.kiwix.kiwixmobile.core.epub.EpubOnDisk
import org.kiwix.kiwixmobile.core.zim_manager.fileselect_view.BooksOnDiskListItem
import org.kiwix.kiwixmobile.core.zim_manager.fileselect_view.BooksOnDiskListItem.BookOnDisk
import org.kiwix.kiwixmobile.core.zim_manager.fileselect_view.SelectionMode
import org.kiwix.kiwixmobile.core.zim_manager.fileselect_view.SelectionMode.MULTI
import org.kiwix.kiwixmobile.core.zim_manager.fileselect_view.SelectionMode.NORMAL

data class FileSelectListState(
  val bookOnDiskListItems: List<BooksOnDiskListItem>,
  val selectionMode: SelectionMode = NORMAL,
  val epubItems: List<EpubOnDisk> = emptyList()
) {
  val selectedBooks
    get() = bookOnDiskListItems
      .filterIsInstance<BookOnDisk>()
      .filter { it.isSelected }

  val selectedEpubs get() = epubItems.filter { it.isSelected }

  val selectedCount get() = selectedBooks.size + selectedEpubs.size

  val isEmpty get() = bookOnDiskListItems.isEmpty() && epubItems.isEmpty()

  /** MULTI while anything (ZIM or EPUB) is selected, otherwise NORMAL. */
  fun withSelectionMode() = copy(selectionMode = if (selectedCount == 0) NORMAL else MULTI)

  fun toggleEpub(id: String) = copy(
    epubItems = epubItems.map { if (it.id == id) it.copy(isSelected = !it.isSelected) else it }
  ).withSelectionMode()

  fun clearSelections() = copy(
    bookOnDiskListItems = bookOnDiskListItems.map {
      if (it is BookOnDisk) it.copy(isSelected = false) else it
    },
    epubItems = epubItems.map { it.copy(isSelected = false) },
    selectionMode = NORMAL
  )
}
