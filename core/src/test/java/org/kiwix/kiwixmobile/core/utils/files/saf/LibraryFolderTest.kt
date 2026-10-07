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

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.UriPermission
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
  private lateinit var context: Context
  private val kiwixDataStore: KiwixDataStore = mockk(relaxed = true)
  private val persistedUriPermissions = mutableListOf<UriPermission>()
  private lateinit var libraryFolder: LibraryFolder

  private fun tree(documentId: String): Uri =
    DocumentsContract.buildTreeDocumentUri(LibraryFolder.EXTERNAL_STORAGE_AUTHORITY, documentId)

  @Before
  fun setUp() {
    val resolver = mockk<ContentResolver>(relaxed = true)
    every { resolver.persistedUriPermissions } answers { persistedUriPermissions.toList() }
    every { resolver.takePersistableUriPermission(any(), any()) } answers {
      val uri = args[0] as Uri
      val flags = args[1] as Int
      persistedUriPermissions.removeAll { it.uri == uri }
      val permission = mockk<UriPermission>()
      every { permission.uri } returns uri
      every { permission.isReadPermission } returns
        (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
      every { permission.isWritePermission } returns
        (flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
      persistedUriPermissions += permission
    }
    val applicationContext: Context = ApplicationProvider.getApplicationContext()
    context = object : ContextWrapper(applicationContext) {
      override fun getContentResolver(): ContentResolver = resolver
    }
    every { kiwixDataStore.publicDocumentsDirectory() } returns File(context.cacheDir, "Documents")
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
  fun mediaScanPickerOpensAtAndroidMediaWhenNothingWasPicked() = runTest {
    coEvery { kiwixDataStore.mediaScanTreeUri } returns flowOf(null)
    assertEquals(
      DocumentsContract.buildDocumentUri(
        LibraryFolder.EXTERNAL_STORAGE_AUTHORITY,
        "primary:Android/media"
      ),
      libraryFolder.mediaScanPickerInitialUri()
    )
  }

  @Test
  fun documentsScanPickerOpensAtDocumentsWhenNothingWasPicked() = runTest {
    coEvery { kiwixDataStore.documentsScanTreeUri } returns flowOf(null)
    assertEquals(
      DocumentsContract.buildDocumentUri(
        LibraryFolder.EXTERNAL_STORAGE_AUTHORITY,
        "primary:Documents"
      ),
      libraryFolder.documentsScanPickerInitialUri()
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
  fun scanTreeNeedsOnlyReadAccess() = runTest {
    val documentsTree = tree("primary:Documents")
    coEvery { kiwixDataStore.libraryTreeUri } returns flowOf(null)
    coEvery { kiwixDataStore.documentsScanTreeUri } returns flowOf(null)
    coEvery { kiwixDataStore.mediaScanTreeUri } returns flowOf(null)

    assertTrue(libraryFolder.onDocumentsScanTreePicked(documentsTree))
    coEvery { kiwixDataStore.documentsScanTreeUri } returns flowOf("$documentsTree")
    assertTrue(libraryFolder.activeScanTreeUris().contains(documentsTree))
    assertFalse(libraryFolder.hasTreeAccess(documentsTree))
  }

  @Test
  fun pickingMediaFolderAddsItWithoutReplacingTheLibraryFolder() = runTest {
    val libraryTree = tree("primary:Documents/Kiwix")
    val mediaTree = tree("primary:Android/media")
    coEvery { kiwixDataStore.libraryTreeUri } returns flowOf("$libraryTree")
    coEvery { kiwixDataStore.documentsScanTreeUri } returns flowOf(null)
    coEvery { kiwixDataStore.mediaScanTreeUri } returns flowOf(null)
    assertTrue(libraryFolder.onTreePicked(libraryTree))
    assertTrue(libraryFolder.onMediaScanTreePicked(mediaTree))
    coEvery { kiwixDataStore.mediaScanTreeUri } returns flowOf("$mediaTree")
    coVerify { kiwixDataStore.setMediaScanTreeUri("$mediaTree") }
    assertEquals(listOf(libraryTree, mediaTree), libraryFolder.activeScanTreeUris())
  }

  @Test
  fun documentsScanTreeIsAddedAlongsideLibraryAndMediaTrees() = runTest {
    val libraryTree = tree("primary:Documents/Kiwix")
    val documentsTree = tree("primary:Documents")
    val mediaTree = tree("primary:Android/media")
    coEvery { kiwixDataStore.libraryTreeUri } returns flowOf("$libraryTree")
    coEvery { kiwixDataStore.documentsScanTreeUri } returns flowOf(null)
    coEvery { kiwixDataStore.mediaScanTreeUri } returns flowOf("$mediaTree")
    libraryFolder.onTreePicked(libraryTree)
    libraryFolder.onMediaScanTreePicked(mediaTree)
    coEvery { kiwixDataStore.mediaScanTreeUri } returns flowOf("$mediaTree")

    assertTrue(libraryFolder.onDocumentsScanTreePicked(documentsTree))
    coEvery { kiwixDataStore.documentsScanTreeUri } returns flowOf("$documentsTree")
    coVerify { kiwixDataStore.setDocumentsScanTreeUri("$documentsTree") }
    assertEquals(
      listOf(libraryTree, documentsTree, mediaTree),
      libraryFolder.activeScanTreeUris()
    )
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
