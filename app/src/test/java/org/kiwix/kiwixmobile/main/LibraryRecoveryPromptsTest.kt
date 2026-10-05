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

package org.kiwix.kiwixmobile.main

import android.content.Context
import android.net.Uri
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kiwix.kiwixmobile.core.StorageObserver
import org.kiwix.kiwixmobile.core.dao.LibkiwixBookOnDisk
import org.kiwix.kiwixmobile.core.utils.dialog.AlertDialogShower
import org.kiwix.kiwixmobile.core.utils.dialog.KiwixDialog
import org.kiwix.kiwixmobile.core.utils.files.saf.LegacyBooksMover
import org.kiwix.kiwixmobile.core.utils.files.saf.LibraryFolder
import org.kiwix.kiwixmobile.core.zim_manager.fileselect_view.BooksOnDiskListItem.BookOnDisk
import java.io.File

class LibraryRecoveryPromptsTest {
  private val context: Context = mockk(relaxed = true)
  private val libraryFolder: LibraryFolder = mockk(relaxed = true)
  private val mover: LegacyBooksMover = mockk(relaxed = true)
  private val storageObserver: StorageObserver = mockk(relaxed = true)
  private val libkiwixBookOnDisk: LibkiwixBookOnDisk = mockk(relaxed = true)
  private val dialogShower: AlertDialogShower = mockk(relaxed = true)
  private val dialog = slot<KiwixDialog>()
  private val pickedAt = mutableListOf<Uri?>()
  private val missingPublicFolder = File("/does/not/exist")
  private lateinit var prompts: LibraryRecoveryPrompts

  @BeforeEach
  fun setUp() {
    clearAllMocks()
    pickedAt.clear()
    coEvery { libraryFolder.savedTreeUri() } returns null
    coEvery { libraryFolder.needsAccessRestore() } returns false
    coEvery { libraryFolder.activeTreeUri() } returns null
    every { libraryFolder.publicLibraryDirectory() } returns missingPublicFolder
    coEvery { libkiwixBookOnDisk.getBooks() } returns emptyList()
    coEvery { mover.offer() } returns null
    prompts = LibraryRecoveryPrompts(
      context,
      libraryFolder,
      mover,
      storageObserver,
      libkiwixBookOnDisk,
      mockk(relaxed = true)
    )
  }

  private suspend fun TestScope.show() = prompts.show(dialogShower, this) { pickedAt += it }

  @Test
  fun `lost folder grant offers to restore at that folder`() = runTest {
    val tree: Uri = mockk()
    val initial: Uri = mockk()
    coEvery { libraryFolder.savedTreeUri() } returns tree
    coEvery { libraryFolder.needsAccessRestore() } returns true
    every { libraryFolder.describe(tree) } returns "Sync/Books"
    coEvery { libraryFolder.pickerInitialUri() } returns initial
    val listeners = slot<Array<() -> Unit>>()
    every { dialogShower.show(capture(dialog), *anyVararg(), uri = any()) } answers {
      @Suppress("UNCHECKED_CAST")
      listeners.captured = (args[1] as Array<() -> Unit>)
    }

    show()
    assertThat(dialog.captured).isEqualTo(KiwixDialog.RestoreLibrary("Sync/Books"))
    listeners.captured.first().invoke()
    advanceUntilIdle()
    assertThat(pickedAt).containsExactly(initial)
  }

  @Test
  fun `empty library with an existing public folder offers to restore Documents`() = runTest {
    every { libraryFolder.publicLibraryDirectory() } returns File(".")
    show()
    verify {
      dialogShower.show(KiwixDialog.RestoreLibrary("Documents/Kiwix"), *anyVararg(), uri = any())
    }
  }

  @Test
  fun `legacy books are offered a move and declining is remembered`() = runTest {
    coEvery { libkiwixBookOnDisk.getBooks() } returns listOf(mockk<BookOnDisk>())
    coEvery { mover.offer() } returns
      LegacyBooksMover.Offer(listOf(mockk()), 2048, "Documents/Kiwix")
    val listeners = slot<Array<() -> Unit>>()
    every { dialogShower.show(capture(dialog), *anyVararg(), uri = any()) } answers {
      @Suppress("UNCHECKED_CAST")
      listeners.captured = (args[1] as Array<() -> Unit>)
    }

    show()
    assertThat(dialog.captured).isEqualTo(KiwixDialog.MoveLegacyBooks(1, "2 KB", "Documents/Kiwix"))
    listeners.captured[1].invoke()
    advanceUntilIdle()
    coVerify { mover.decline() }
  }

  @Test
  fun `nothing to recover shows nothing`() = runTest {
    coEvery { libkiwixBookOnDisk.getBooks() } returns listOf(mockk<BookOnDisk>())
    show()
    verify(exactly = 0) { dialogShower.show(any(), *anyVararg(), uri = any()) }
  }
}
