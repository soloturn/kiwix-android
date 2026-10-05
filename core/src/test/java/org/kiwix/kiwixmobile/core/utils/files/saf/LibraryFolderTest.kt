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
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kiwix.kiwixmobile.core.utils.datastore.KiwixDataStore
import org.kiwix.sharedFunctions.TestApplication
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R], application = TestApplication::class)
class LibraryFolderTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val kiwixDataStore: KiwixDataStore = mockk(relaxed = true)
  private lateinit var libraryFolder: LibraryFolder

  private fun tree(documentId: String): Uri =
    DocumentsContract.buildTreeDocumentUri(LibraryFolder.EXTERNAL_STORAGE_AUTHORITY, documentId)

  @Before
  fun setUp() {
    every { kiwixDataStore.publicDocumentsDirectory() } returns
      Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
    libraryFolder = LibraryFolder(context, kiwixDataStore)
  }

  @Test
  fun pickerOpensAtDocumentsKiwixWhenNothingWasPicked() = runTest {
    coEvery { kiwixDataStore.libraryTreeUri } returns flowOf(null)
    assertEquals(
      DocumentsContract.buildDocumentUri(
        LibraryFolder.EXTERNAL_STORAGE_AUTHORITY,
        "primary:Documents/Kiwix"
      ),
      libraryFolder.pickerInitialUri()
    )
  }

  @Test
  fun pickerReopensTheLastPickedFolderEvenWithoutAGrant() = runTest {
    val picked = tree("primary:Sync/Books")
    coEvery { kiwixDataStore.libraryTreeUri } returns flowOf("$picked")
    assertEquals(
      DocumentsContract.buildDocumentUriUsingTree(picked, "primary:Sync/Books"),
      libraryFolder.pickerInitialUri()
    )
    assertNull(libraryFolder.activeTreeUri())
    assertTrue(libraryFolder.needsAccessRestore())
  }

  @Test
  fun pickingAFolderPersistsGrantAndUri() = runTest {
    val picked = tree("primary:Sync/Books")
    coEvery { kiwixDataStore.libraryTreeUri } returns flowOf(null)
    assertTrue(libraryFolder.onTreePicked(picked))
    assertTrue(libraryFolder.hasTreeAccess(picked))
    coVerify { kiwixDataStore.setLibraryTreeUri("$picked") }

    coEvery { kiwixDataStore.libraryTreeUri } returns flowOf("$picked")
    assertEquals(picked, libraryFolder.activeTreeUri())
    assertFalse(libraryFolder.needsAccessRestore())
  }

  @Test
  fun picksInsideAGrantedTreeNeedNoPerFileGrant() = runTest {
    coEvery { kiwixDataStore.libraryTreeUri } returns flowOf(null)
    libraryFolder.onTreePicked(tree("primary:Sync/Books"))
    val inside = DocumentsContract.buildDocumentUri(
      LibraryFolder.EXTERNAL_STORAGE_AUTHORITY,
      "primary:Sync/Books/sub/wiki.zim"
    )
    val outside = DocumentsContract.buildDocumentUri(
      LibraryFolder.EXTERNAL_STORAGE_AUTHORITY,
      "primary:Sync/BooksOther/wiki.zim"
    )
    assertEquals(
      DocumentsContract.buildDocumentUriUsingTree(
        tree("primary:Sync/Books"),
        "primary:Sync/Books/sub/wiki.zim"
      ),
      LibraryFolder.documentInGrantedTree(context.contentResolver, inside)
    )
    assertNull(LibraryFolder.documentInGrantedTree(context.contentResolver, outside))
  }

  @Test
  fun describesPrimaryAndSecondaryVolumes() {
    assertEquals("Documents/Kiwix", libraryFolder.describe(tree("primary:Documents/Kiwix")))
    assertEquals("1A2B-3C4D: Books", libraryFolder.describe(tree("1A2B-3C4D:Books")))
  }

  @Test
  fun primaryTreeResolvesToExternalStorageVolume() {
    val directory = libraryFolder.volumeDirectory(tree("primary:Missing/Folder"))
    assertEquals(Environment.getExternalStorageDirectory(), directory)
    assertNull(libraryFolder.volumeDirectory(tree("FFFF-0000:Books")))
    val cloud = DocumentsContract.buildTreeDocumentUri("com.example.cloud", "root")
    assertNull(libraryFolder.volumeDirectory(cloud))
    assertNull(libraryFolder.availableBytes(cloud))
  }

  @Test
  fun publicDocumentsIsRecognised() {
    assertTrue(libraryFolder.isPublicDocuments(libraryFolder.publicDocumentsDirectory()))
    assertFalse(libraryFolder.isPublicDocuments(File(context.filesDir, "x")))
    assertEquals(
      File(libraryFolder.publicDocumentsDirectory(), "Kiwix"),
      libraryFolder.publicLibraryDirectory()
    )
  }
}
