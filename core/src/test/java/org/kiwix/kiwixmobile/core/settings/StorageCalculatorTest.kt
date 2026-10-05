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

package org.kiwix.kiwixmobile.core.settings

import android.net.Uri
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.kiwix.kiwixmobile.core.R
import org.kiwix.kiwixmobile.core.utils.datastore.KiwixDataStore
import org.kiwix.kiwixmobile.core.utils.files.saf.LibraryFolder
import org.kiwix.sharedFunctions.MainDispatcherRule
import java.io.File

internal class StorageCalculatorTest {
  @RegisterExtension
  @JvmField
  val mainDispatcherRule = MainDispatcherRule()
  private val libraryFolder: LibraryFolder = mockk {
    coEvery { activeTreeUri() } returns null
  }
  private val kiwixDataStore: KiwixDataStore = mockk(relaxed = true)
  private val storageCalculator =
    StorageCalculator(kiwixDataStore, mainDispatcherRule.dispatcher, libraryFolder)
  private val file: File = mockk()

  @Test
  fun `library folder free space comes from its volume`() = runTest {
    val tree: Uri = mockk()
    coEvery { libraryFolder.activeTreeUri() } returns tree
    every { libraryFolder.availableBytes(tree) } returns 2048L
    assertThat(storageCalculator.availableBytes()).isEqualTo(2048L)
    assertThat(storageCalculator.calculateAvailableSpace()).isEqualTo("2 KB")
  }

  @Test
  fun `unknown library folder volume does not block and is shown as unknown`() = runTest {
    val tree: Uri = mockk()
    coEvery { libraryFolder.activeTreeUri() } returns tree
    every { libraryFolder.availableBytes(tree) } returns null
    every { kiwixDataStore.context.getString(R.string.unknown_free_space) } returns "Unknown"
    assertThat(storageCalculator.availableBytes()).isEqualTo(Long.MAX_VALUE)
    assertThat(storageCalculator.calculateAvailableSpace()).isEqualTo("Unknown")
  }

  @Test
  fun `calculate available space with existing file`() =
    runTest {
      every { file.freeSpace } returns 1
      every { file.exists() } returns true
      assertThat(storageCalculator.calculateAvailableSpace(file)).isEqualTo("1 Bytes")
    }

  @Test
  fun `calculate total space of existing file`() =
    runTest {
      every { file.totalSpace } returns 1
      every { file.exists() } returns true
      assertThat(storageCalculator.calculateTotalSpace(file)).isEqualTo("1 Bytes")
    }

  @Test
  fun `calculate total space of non existing file`() =
    runTest {
      every { file.exists() } returns false
      assertThat(storageCalculator.calculateTotalSpace(file)).isEqualTo("0 Bytes")
    }

  @Test
  fun `available bytes of non existing file`() =
    runTest {
      every { file.exists() } returns false
      assertThat(storageCalculator.availableBytes(file)).isEqualTo(0L)
    }
}
