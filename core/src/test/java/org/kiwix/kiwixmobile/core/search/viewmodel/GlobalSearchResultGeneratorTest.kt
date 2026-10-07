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

package org.kiwix.kiwixmobile.core.search.viewmodel

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verifyOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.kiwix.kiwixmobile.core.dao.LibkiwixBookOnDisk
import org.kiwix.kiwixmobile.core.reader.ZimFileReader
import org.kiwix.kiwixmobile.core.reader.ZimReaderSource
import org.kiwix.kiwixmobile.core.zim_manager.fileselect_view.BooksOnDiskListItem.BookOnDisk
import org.kiwix.sharedFunctions.MainDispatcherRule
import org.kiwix.sharedFunctions.libkiwixBook

internal class GlobalSearchResultGeneratorTest {
  @RegisterExtension
  @JvmField
  val mainDispatcherRule = MainDispatcherRule()

  private val libkiwixBookOnDisk: LibkiwixBookOnDisk = mockk()
  private val zimFileReaderFactory: ZimFileReader.Factory = mockk()
  private val generator = GlobalSearchResultGeneratorImpl(
    libkiwixBookOnDisk,
    zimFileReaderFactory,
    mainDispatcherRule.dispatcher
  )

  private fun bookOnDisk(title: String, databaseValue: String): BookOnDisk {
    val source: ZimReaderSource = mockk()
    every { source.toDatabase() } returns databaseValue
    every { source.releaseDescriptors() } just Runs
    return BookOnDisk(book = libkiwixBook(title = title), zimReaderSource = source)
  }

  private fun suggestionIterator(vararg titles: String): SuggestionIteratorWrapper {
    val iterator: SuggestionIteratorWrapper = mockk()
    val items = titles.map { title ->
      mockk<SuggestionItemWrapper>().also {
        every { it.title } returns title
        every { it.path } returns "/$title"
      }
    }
    every { iterator.hasNext() } returnsMany (List(titles.size) { true } + false)
    every { iterator.next() } returnsMany items
    every { iterator.dispose() } just Runs
    return iterator
  }

  private fun readerWithTitles(vararg titles: String): ZimFileReader {
    val reader: ZimFileReader = mockk()
    val search: SuggestionSearchWrapper = mockk()
    every { reader.searchSuggestions(any()) } returns search
    every { search.getResults(any(), any()) } returns suggestionIterator(*titles)
    every { search.dispose() } just Runs
    return reader
  }

  @Test
  internal fun `results from each book are tagged with the book title and source`() = runTest {
    val bookA = bookOnDisk("Book A", "db-a")
    val bookB = bookOnDisk("Book B", "db-b")
    coEvery { libkiwixBookOnDisk.getBooks() } returns listOf(bookA, bookB)
    coEvery { zimFileReaderFactory.create(bookA.zimReaderSource, false) } returns
      readerWithTitles("alpha")
    coEvery { zimFileReaderFactory.create(bookB.zimReaderSource, false) } returns
      readerWithTitles("beta")

    val results = generator.generateSearchResults("a", SearchMode.TITLE, SearchCancelToken())

    assertThat(results).hasSize(2)
    assertThat(results[0].bookTitle).isEqualTo("Book A")
    assertThat(results[0].zimReaderSourceDatabaseValue).isEqualTo("db-a")
    assertThat(results[1].bookTitle).isEqualTo("Book B")
    assertThat(results[1].zimReaderSourceDatabaseValue).isEqualTo("db-b")
  }

  @Test
  internal fun `global search stops between books when cancelled`() = runTest {
    val bookA = bookOnDisk("Book A", "db-a")
    val bookB = bookOnDisk("Book B", "db-b")
    val cancelToken = SearchCancelToken()
    coEvery { libkiwixBookOnDisk.getBooks() } returns listOf(bookA, bookB)
    val readerA: ZimFileReader = mockk()
    val search: SuggestionSearchWrapper = mockk()
    val iterator: SuggestionIteratorWrapper = mockk()
    val entry: SuggestionItemWrapper = mockk()
    every { readerA.searchSuggestions(any()) } returns search
    every { search.getResults(any(), any()) } returns iterator
    every { search.dispose() } just Runs
    every { entry.title } returns "alpha"
    every { entry.path } returns "/alpha"
    every { iterator.hasNext() } returns true
    every { iterator.next() } answers {
      cancelToken.cancel()
      entry
    }
    every { iterator.dispose() } just Runs
    coEvery { zimFileReaderFactory.create(bookA.zimReaderSource, false) } returns readerA

    generator.generateSearchResults("a", SearchMode.TITLE, cancelToken)

    coVerify(exactly = 0) { zimFileReaderFactory.create(bookB.zimReaderSource, any()) }
  }

  @Test
  internal fun `global search stops between results within a book`() = runTest {
    val book = bookOnDisk("Book A", "db-a")
    val cancelToken = SearchCancelToken()
    coEvery { libkiwixBookOnDisk.getBooks() } returns listOf(book)
    val reader: ZimFileReader = mockk()
    val search: SuggestionSearchWrapper = mockk()
    val iterator: SuggestionIteratorWrapper = mockk()
    val entry: SuggestionItemWrapper = mockk()
    every { reader.searchSuggestions(any()) } returns search
    every { search.getResults(any(), any()) } returns iterator
    every { search.dispose() } just Runs
    every { entry.title } returns "alpha"
    every { entry.path } returns "/alpha"
    every { iterator.hasNext() } returnsMany listOf(true, true, true, false)
    every { iterator.next() } answers {
      cancelToken.cancel()
      entry
    }
    every { iterator.dispose() } just Runs
    coEvery { zimFileReaderFactory.create(book.zimReaderSource, false) } returns reader

    val results = generator.generateSearchResults("a", SearchMode.TITLE, cancelToken)

    assertThat(results).hasSize(1)
  }

  @Test
  internal fun `global search rethrows CancellationException and still disposes the reader`() =
    runTest {
      val book = bookOnDisk("Book A", "db-a")
      val cancelToken = SearchCancelToken()
      coEvery { libkiwixBookOnDisk.getBooks() } returns listOf(book)
      val reader: ZimFileReader = mockk()
      val search: SuggestionSearchWrapper = mockk()
      val iterator: SuggestionIteratorWrapper = mockk()
      every { reader.searchSuggestions(any()) } returns search
      every { search.getResults(any(), any()) } returns iterator
      every { search.dispose() } just Runs
      every { iterator.hasNext() } returns true
      every { iterator.next() } throws CancellationException("match aborted")
      every { iterator.dispose() } just Runs
      coEvery { zimFileReaderFactory.create(book.zimReaderSource, false) } returns reader

      val thrown = runCatching {
        generator.generateSearchResults("a", SearchMode.TITLE, cancelToken)
      }.exceptionOrNull()

      assertThat(thrown).isInstanceOf(CancellationException::class.java)
      verifyOrder {
        reader.dispose()
        book.zimReaderSource.releaseDescriptors()
      }
    }

  @Test
  internal fun `unreadable books are skipped`() = runTest {
    val broken = bookOnDisk("Broken", "db-broken")
    val healthy = bookOnDisk("Healthy", "db-healthy")
    coEvery { libkiwixBookOnDisk.getBooks() } returns listOf(broken, healthy)
    coEvery { zimFileReaderFactory.create(broken.zimReaderSource, false) } returns null
    coEvery { zimFileReaderFactory.create(healthy.zimReaderSource, false) } returns
      readerWithTitles("alpha")

    val results = generator.generateSearchResults("a", SearchMode.TITLE, SearchCancelToken())

    assertThat(results).hasSize(1)
    assertThat(results[0].bookTitle).isEqualTo("Healthy")
  }

  @Test
  internal fun `the reader and its descriptors are released after every book`() = runTest {
    val book = bookOnDisk("Book A", "db-a")
    coEvery { libkiwixBookOnDisk.getBooks() } returns listOf(book)
    val reader = readerWithTitles("alpha")
    coEvery { zimFileReaderFactory.create(book.zimReaderSource, false) } returns reader

    generator.generateSearchResults("a", SearchMode.TITLE, SearchCancelToken())

    verifyOrder {
      reader.dispose()
      book.zimReaderSource.releaseDescriptors()
    }
  }
}
