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
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kiwix.kiwixmobile.core.dao.HistoryRoomDao
import org.kiwix.kiwixmobile.core.dao.LibkiwixBookOnDisk
import org.kiwix.kiwixmobile.core.dao.NotesRoomDao
import org.kiwix.kiwixmobile.core.downloader.downloadManager.DownloadTargets
import org.kiwix.kiwixmobile.core.reader.ZimReaderSource
import org.kiwix.kiwixmobile.core.utils.datastore.KiwixDataStore
import org.kiwix.kiwixmobile.core.zim_manager.fileselect_view.BooksOnDiskListItem.BookOnDisk
import org.kiwix.sharedFunctions.MainDispatcherRule
import org.kiwix.sharedFunctions.TestApplication
import org.kiwix.sharedFunctions.libkiwixBook
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R], application = TestApplication::class)
class LegacyBooksMoverTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val kiwixDataStore: KiwixDataStore = mockk(relaxed = true)
  private val libraryFolder: LibraryFolder = mockk(relaxed = true)
  private val libkiwixBookOnDisk: LibkiwixBookOnDisk = mockk(relaxed = true)
  private val downloadTargets: DownloadTargets = mockk()
  private val historyRoomDao: HistoryRoomDao = mockk(relaxed = true)
  private val notesRoomDao: NotesRoomDao = mockk(relaxed = true)
  private lateinit var documentsKiwix: File
  private lateinit var mover: LegacyBooksMover

  @Before
  fun setUp() {
    documentsKiwix = tempFolder.newFolder("Documents", "Kiwix")
    coEvery { kiwixDataStore.legacyBooksMoveDeclined } returns flowOf(false)
    coEvery { kiwixDataStore.defaultLibraryStorage() } returns documentsKiwix.parent!!
    coEvery { kiwixDataStore.currentZimFile } returns flowOf(null)
    coEvery { libraryFolder.activeTreeUri() } returns null
    every { libraryFolder.isPublicDocuments(any()) } returns true
    coEvery { downloadTargets.create(any()) } answers { File(documentsKiwix, firstArg()).path }
    every { downloadTargets.finish(any()) } answers { firstArg() }
    mover = LegacyBooksMover(
      context,
      kiwixDataStore,
      libraryFolder,
      libkiwixBookOnDisk,
      downloadTargets,
      historyRoomDao,
      notesRoomDao,
      mainDispatcherRule.dispatcher
    )
  }

  private fun appSpecificBook(name: String, content: String = "zim-$name"): BookOnDisk {
    val file = File(context.getExternalFilesDir(null), "Kiwix/$name").apply {
      parentFile?.mkdirs()
      writeText(content)
    }
    return BookOnDisk(book = libkiwixBook(id = name), zimReaderSource = ZimReaderSource(file))
  }

  @Test
  fun offersOnlyBooksInAppSpecificStorage() = runTest(mainDispatcherRule.dispatcher) {
    val legacy = appSpecificBook("wiki.zim", "12345")
    val elsewhere = File(documentsKiwix, "other.zim").apply { writeText("x") }
    coEvery { libkiwixBookOnDisk.getBooks() } returns listOf(
      legacy,
      BookOnDisk(book = libkiwixBook(id = "other"), zimReaderSource = ZimReaderSource(elsewhere))
    )

    val offer = mover.offer()!!
    assertEquals(listOf(legacy), offer.books)
    assertEquals(5L, offer.totalBytes)
    assertEquals("Documents/Kiwix", offer.destination)
  }

  @Test
  fun noOfferOnceDeclinedOrWithoutBooks() = runTest(mainDispatcherRule.dispatcher) {
    coEvery { libkiwixBookOnDisk.getBooks() } returns emptyList()
    assertNull(mover.offer())

    coEvery { libkiwixBookOnDisk.getBooks() } returns listOf(appSpecificBook("wiki.zim"))
    coEvery { kiwixDataStore.legacyBooksMoveDeclined } returns flowOf(true)
    assertNull(mover.offer())
  }

  @Test
  fun movesCopiesRegistersAndOnlyThenDeletes() = runTest(mainDispatcherRule.dispatcher) {
    val book = appSpecificBook("wiki.zim", "zim content")
    val oldPath = book.zimReaderSource.file!!.canonicalPath
    val newPath = File(documentsKiwix, "wiki.zim").path
    val progress = mutableListOf<Pair<Int, Int>>()

    val result = mover.move(LegacyBooksMover.Offer(listOf(book), 11, "Documents/Kiwix")) { done, all ->
      progress += done to all
    }

    assertEquals(LegacyBooksMover.Result(moved = 1, failed = 0), result)
    assertEquals("zim content", File(newPath).readText())
    assertFalse(File(oldPath).exists())
    assertEquals(listOf(1 to 1), progress)
    // Without a picked folder, future downloads follow the books to Documents.
    coVerify { kiwixDataStore.setSelectedStorage(any()) }
    coVerify(ordering = io.mockk.Ordering.ORDERED) {
      libkiwixBookOnDisk.delete("wiki.zim")
      libkiwixBookOnDisk.insertLocation(newPath)
    }
    verify { historyRoomDao.relocateBook(oldPath, newPath) }
    verify { notesRoomDao.relocateBook(oldPath, newPath) }
  }

  @Test
  fun splitBooksMoveAllParts() = runTest(mainDispatcherRule.dispatcher) {
    val first = appSpecificBook("split.zimaa", "a")
    val second = File(first.zimReaderSource.file!!.parentFile, "split.zimab").apply { writeText("b") }

    mover.move(LegacyBooksMover.Offer(listOf(first), 2, "Documents/Kiwix"))

    assertEquals("a", File(documentsKiwix, "split.zimaa").readText())
    assertEquals("b", File(documentsKiwix, "split.zimab").readText())
    assertFalse(second.exists())
    coVerify { libkiwixBookOnDisk.insertLocation(File(documentsKiwix, "split.zimaa").path) }
  }

  @Test
  fun failedCopyKeepsTheOriginal() = runTest(mainDispatcherRule.dispatcher) {
    val book = appSpecificBook("wiki.zim")
    every { downloadTargets.finish(any()) } returns null

    val result = mover.move(LegacyBooksMover.Offer(listOf(book), 1, "Documents/Kiwix"))

    assertEquals(LegacyBooksMover.Result(moved = 0, failed = 1), result)
    assertTrue(book.zimReaderSource.file!!.exists())
    coVerify(exactly = 0) { libkiwixBookOnDisk.delete(any<String>()) }
  }
}
