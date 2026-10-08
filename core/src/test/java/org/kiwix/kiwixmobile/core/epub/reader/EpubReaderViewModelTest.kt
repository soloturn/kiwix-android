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

package org.kiwix.kiwixmobile.core.epub.reader

import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kiwix.kiwixmobile.core.dao.entities.EpubBookRoomEntity
import org.kiwix.kiwixmobile.core.epub.EpubLibraryManager
import org.kiwix.kiwixmobile.core.utils.datastore.KiwixDataStore
import org.kiwix.sharedFunctions.MainDispatcherRule
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R])
class EpubReaderViewModelTest {
  @get:Rule
  val tmp = TemporaryFolder()

  @get:Rule
  val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

  private val library: EpubLibraryManager = mockk()
  private val store: KiwixDataStore = mockk()
  private val opener =
    EpubPublicationOpener(ApplicationProvider.getApplicationContext(), Dispatchers.IO)

  private fun entity(id: String, path: String) =
    EpubBookRoomEntity(id, path, "t", "a", "en", null, 1L, 0L, 0L)

  private fun locator(path: String, progression: Double) =
    Locator(href = Url(path)!!, mediaType = MediaType.XHTML)
      .copyWithLocations(progression = progression)

  private fun viewModel(
    path: String?,
    settings: EpubReaderSettings = EpubReaderSettings(),
    savedLocator: String? = null
  ): EpubReaderViewModel {
    every { store.epubReaderSettings } returns flowOf(settings)
    coEvery { store.getEpubLocator(BOOK_ID) } returns savedLocator
    coEvery { store.setEpubLocator(any(), any()) } just Runs
    coEvery { store.setEpubReaderSettings(any()) } just Runs
    coEvery { library.add(any(), any()) } answers {
      entity(BOOK_ID, firstArg<File>().absolutePath)
    }
    return EpubReaderViewModel(
      SavedStateHandle(path?.let { mapOf(EpubReaderViewModel.EXTRA_PATH to it) } ?: emptyMap()),
      opener,
      library,
      store
    )
  }

  private suspend fun EpubReaderViewModel.ready(): OpenEpub =
    (state.first { it !is EpubReaderUiState.Loading } as EpubReaderUiState.Ready).book

  private fun book() = EpubFixture.write(tmp.newFile("book.epub"))

  @Test
  fun `opens the book, adds it to the library and exposes title and toc`() = runTest {
    val file = book()
    val vm = viewModel(file.path, EpubReaderSettings(fontScale = 1.4))

    val open = vm.ready()

    assertEquals(BOOK_ID, open.bookId)
    assertEquals(EpubFixture.TITLE, open.title)
    assertEquals(listOf("Chapter One", "Chapter Two"), open.toc.map { it.title })
    assertEquals(null, open.initialLocator)
    assertEquals(1.4, vm.settings.value.fontScale, 0.0)
    coVerify { library.add(file, true) }
  }

  @Test
  fun `restores the stored locator`() = runTest {
    val saved = locator("c2.xhtml", 0.5)
    val vm = viewModel(book().path, savedLocator = EpubLocatorCodec.encode(saved))

    assertEquals(saved, vm.ready().initialLocator)
    assertEquals(saved, vm.currentLocator.value)
  }

  @Test
  fun `a corrupt stored locator opens at the start`() = runTest {
    val vm = viewModel(book().path, savedLocator = "{garbage")
    assertEquals(null, vm.ready().initialLocator)
  }

  @Test
  fun `missing, absent and invalid files fail instead of crashing`() = runTest {
    val junk = tmp.newFile("junk.epub").apply { writeText("not a zip") }
    listOf(null, File(tmp.root, "gone.epub").path, junk.path).forEach { path ->
      val vm = viewModel(path)
      assertEquals(EpubReaderUiState.Failed, vm.state.first { it !is EpubReaderUiState.Loading })
    }
  }

  @Test
  fun `page turns are saved after a pause, only the latest one`() = runTest {
    val vm = viewModel(book().path)
    vm.ready()

    vm.onLocatorChanged(locator("c1.xhtml", 0.1))
    advanceTimeBy(300)
    vm.onLocatorChanged(locator("c1.xhtml", 0.2))
    advanceTimeBy(300)
    runCurrent()
    coVerify(exactly = 0) { store.setEpubLocator(any(), any()) }

    advanceTimeBy(300)
    runCurrent()
    coVerify(exactly = 1) {
      store.setEpubLocator(BOOK_ID, EpubLocatorCodec.encode(locator("c1.xhtml", 0.2)))
    }
  }

  @Test
  fun `persistPosition writes immediately`() = runTest {
    val vm = viewModel(book().path)
    vm.ready()
    val latest = locator("c2.xhtml", 0.9)

    vm.onLocatorChanged(latest)
    vm.persistPosition()
    runCurrent()

    coVerify(exactly = 1) { store.setEpubLocator(BOOK_ID, EpubLocatorCodec.encode(latest)) }
  }

  @Test
  fun `settings changes are applied and stored, repeats are ignored`() = runTest {
    val vm = viewModel(book().path)
    vm.ready()

    vm.changeSettings { it.withFontScale(1.1) }
    vm.changeSettings { it.withFontScale(1.2).withFontScale(1.0) }
    vm.changeSettings { it }
    runCurrent()

    assertEquals(1.0, vm.settings.value.fontScale, 0.0)
    coVerify(exactly = 1) { store.setEpubReaderSettings(EpubReaderSettings(fontScale = 1.1)) }
    coVerify(exactly = 1) { store.setEpubReaderSettings(EpubReaderSettings(fontScale = 1.0)) }
  }

  @Test
  fun `the overlay starts hidden and can be shown and hidden`() = runTest {
    val vm = viewModel(book().path)
    assertFalse(vm.chromeVisible.value)
    vm.setChromeVisible(true)
    assertTrue(vm.chromeVisible.value)
    vm.setChromeVisible(false)
    assertFalse(vm.chromeVisible.value)
  }

  @Test
  fun `positions are loaded once the book is open`() = runTest {
    val vm = viewModel(book().path)
    vm.ready()
    assertTrue(vm.positions.first { it.isNotEmpty() }.isNotEmpty())
  }

  private companion object {
    const val BOOK_ID = "book-id"
  }
}
