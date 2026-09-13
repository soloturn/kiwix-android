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

package org.kiwix.kiwixmobile.nav.destination.library.local

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import org.kiwix.kiwixmobile.core.entity.LibkiwixBook
import org.kiwix.kiwixmobile.core.reader.ZimReaderSource
import org.kiwix.kiwixmobile.core.ui.theme.KiwixTheme
import org.kiwix.kiwixmobile.core.zim_manager.fileselect_view.BooksOnDiskListItem
import org.kiwix.kiwixmobile.core.zim_manager.fileselect_view.BooksOnDiskListItem.BookOnDisk
import org.kiwix.kiwixmobile.zimManager.fileselectView.FileSelectListState
import java.io.File
import java.util.Locale

/**
 * Builds a fake [BookOnDisk] with no on-disk backing (this only exercises rendering, never I/O
 * or libkiwix), so this Preview screenshot test has no dependency on Hilt, a ViewModel, or an
 * actual ZIM file.
 */
private fun sampleBookOnDisk(
  id: String,
  title: String,
  description: String,
  articleCount: String,
  sizeInBytes: String
): BookOnDisk {
  val book = LibkiwixBook().apply {
    this.id = id
    this.title = title
    this.description = description
    language = "eng"
    creator = "Wikimedia Foundation"
    publisher = "openZIM"
    date = "2024-01-01"
    this.articleCount = articleCount
    size = sizeInBytes
  }
  return BookOnDisk(book = book, zimReaderSource = ZimReaderSource(File("")))
}

private fun sampleBookList(): List<BooksOnDiskListItem> =
  listOf(
    BooksOnDiskListItem.LanguageItem(Locale.ENGLISH),
    sampleBookOnDisk(
      id = "wikipedia_en_all",
      title = "Wikipedia",
      description = "The free encyclopedia, offline.",
      articleCount = "6800000",
      sizeInBytes = "96636764160"
    ),
    sampleBookOnDisk(
      id = "wiktionary_en_all",
      title = "Wiktionary",
      description = "A collaborative dictionary, freely available to everyone.",
      articleCount = "1100000",
      sizeInBytes = "3221225472"
    ),
    sampleBookOnDisk(
      id = "wikivoyage_en_all",
      title = "Wikivoyage",
      description = "A free, worldwide travel guide, written by volunteers.",
      articleCount = "45000",
      sizeInBytes = "524288000"
    )
  )

@PreviewTest
@Preview(showBackground = true)
@Composable
fun BookItemListPreview() {
  KiwixTheme {
    BookItemListForPreview(
      state = FileSelectListState(bookOnDiskListItems = sampleBookList()),
      lazyListState = rememberLazyListState()
    )
  }
}
