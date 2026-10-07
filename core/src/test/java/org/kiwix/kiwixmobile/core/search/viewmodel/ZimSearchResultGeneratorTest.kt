/*
 * Kiwix Android
 * Copyright (c) 2020 Kiwix <android.kiwix.org>
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

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.kiwix.kiwixmobile.core.reader.ZimFileReader
import org.kiwix.kiwixmobile.core.reader.ZimReaderContainer
import org.kiwix.sharedFunctions.MainDispatcherRule

internal class ZimSearchResultGeneratorTest {
  @RegisterExtension
  @JvmField
  val mainDispatcherRule = MainDispatcherRule()
  private val zimFileReader: ZimFileReader = mockk()
  private val zimReaderContainer: ZimReaderContainer = mockk()

  private val zimSearchResultGenerator: ZimSearchResultGenerator = ZimSearchResultGenerator()

  private suspend fun stubWithReader() {
    coEvery { zimReaderContainer.withReader<Any?>(any()) } coAnswers {
      firstArg<(ZimFileReader) -> Any?>().invoke(zimFileReader)
    }
  }

  @Test
  internal fun `empty search term returns empty list`() = runTest {
    assertThat(zimSearchResultGenerator.generateSearchResults("", SearchMode.TITLE, zimReaderContainer))
      .isEqualTo(null)
  }

  @Test
  internal fun `suggestion results are distinct`() = runTest {
    val searchTerm = "a"
    val suggestionSearchWrapper: SuggestionSearchWrapper = mockk()
    every { zimFileReader.searchSuggestions(searchTerm) } returns suggestionSearchWrapper
    stubWithReader()
    assertThat(
      zimSearchResultGenerator.generateSearchResults(searchTerm, SearchMode.TITLE, zimReaderContainer)
    ).isEqualTo(ZimSearchResultSet.Title(suggestionSearchWrapper))
    verify {
      zimFileReader.searchSuggestions(searchTerm)
    }
  }

  @Test
  internal fun `page content mode uses full text search`() = runTest {
    val searchTerm = "a"
    val searchWrapper: SearchWrapper = mockk()
    every { zimFileReader.searchFullText(searchTerm) } returns searchWrapper
    stubWithReader()
    assertThat(
      zimSearchResultGenerator.generateSearchResults(
        searchTerm,
        SearchMode.PAGE_CONTENT,
        zimReaderContainer
      )
    ).isEqualTo(ZimSearchResultSet.PageContent(searchWrapper))
  }

  @Test
  internal fun `page content mode falls back to title search without full text index`() = runTest {
    val searchTerm = "a"
    val suggestionSearchWrapper: SuggestionSearchWrapper = mockk()
    every { zimFileReader.searchFullText(searchTerm) } returns null
    every { zimFileReader.searchSuggestions(searchTerm) } returns suggestionSearchWrapper
    stubWithReader()
    assertThat(
      zimSearchResultGenerator.generateSearchResults(
        searchTerm,
        SearchMode.PAGE_CONTENT,
        zimReaderContainer
      )
    ).isEqualTo(ZimSearchResultSet.Title(suggestionSearchWrapper))
  }
}
