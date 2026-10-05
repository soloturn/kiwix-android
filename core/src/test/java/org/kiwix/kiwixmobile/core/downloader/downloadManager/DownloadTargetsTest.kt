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

package org.kiwix.kiwixmobile.core.downloader.downloadManager

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kiwix.kiwixmobile.core.utils.datastore.KiwixDataStore
import org.kiwix.kiwixmobile.core.utils.files.saf.LibraryFolder
import org.kiwix.sharedFunctions.TestApplication
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R], application = TestApplication::class)
class DownloadTargetsTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  private val resolver: ContentResolver = mockk(relaxed = true)
  private val context: Context = mockk(relaxed = true) {
    every { contentResolver } returns resolver
  }
  private val kiwixDataStore: KiwixDataStore = mockk(relaxed = true)
  private val libraryFolder: LibraryFolder = mockk(relaxed = true)
  private val authority = "com.android.externalstorage.documents"
  private val rootId = "primary:Sync/Books"
  private val tree = DocumentsContract.buildTreeDocumentUri(authority, rootId)
  private lateinit var targets: DownloadTargets

  // DocumentsContract.EXTRA_URI is hidden from the SDK.
  private val extraUri = "uri"

  @Before
  fun setUp() {
    coEvery { kiwixDataStore.isPlayStoreBuild } returns flowOf(true)
    coEvery { libraryFolder.activeTreeUri() } returns null
    coEvery { kiwixDataStore.selectedStorage } returns flowOf("/storage/emulated/0/Documents")
    every { libraryFolder.isPublicDocuments(any()) } returns false
    mockkStatic(Environment::class)
    every { Environment.isExternalStorageManager() } returns false
    targets = DownloadTargets(context, kiwixDataStore, libraryFolder)
  }

  @After
  fun tearDown() {
    unmockkStatic(Environment::class)
  }

  private fun document(name: String) =
    DocumentsContract.buildDocumentUriUsingTree(tree, "$rootId/$name")

  private fun children(vararg names: String) =
    MatrixCursor(
      arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE
      )
    ).apply { names.forEach { addRow(arrayOf<Any>("$rootId/$it", it, "application/x-zim", 1L)) } }

  @Test
  fun appSpecificStorageGetsAPlainPath() = runTest {
    coEvery { kiwixDataStore.selectedStorage } returns flowOf("/storage/emulated/0/Android/media/x")
    assertEquals(
      "/storage/emulated/0/Android/media/x/Kiwix/wiki.zim",
      targets.create("wiki.zim")
    )
    assertEquals("/a/b.zim", targets.finish("/a/b.zim"))
  }

  @Test
  fun libraryFolderGetsAPartialDocumentThatIsRenamedWhenDone() = runTest {
    coEvery { libraryFolder.activeTreeUri() } returns tree
    every { resolver.query(any(), any(), any(), any(), any()) } returns children("other.zim")
    val created = document("wiki.zim.part")
    every {
      resolver.call(authority, "android:createDocument", null, any())
    } returns Bundle().apply { putParcelable(extraUri, created) }

    assertEquals("$created", targets.create("wiki.zim"))

    every { resolver.query(created, any(), any(), any(), any()) } returns
      children("wiki.zim.part")
    val renamed = document("wiki.zim")
    every {
      resolver.call(authority, "android:renameDocument", null, any())
    } returns Bundle().apply { putParcelable(extraUri, renamed) }

    assertEquals("$renamed", targets.finish("$created"))
  }

  @Test
  fun interruptedPartialDocumentIsReused() = runTest {
    coEvery { libraryFolder.activeTreeUri() } returns tree
    every { resolver.query(any(), any(), any(), any(), any()) } returns children("wiki.zim.part")

    assertEquals("${document("wiki.zim.part")}", targets.create("wiki.zim"))
    verify(exactly = 0) { resolver.call(any<String>(), any(), any(), any()) }
  }

  @Test
  fun documentsDefaultIsAPendingMediaStoreRowOnPlayBuilds() = runTest {
    coEvery { kiwixDataStore.selectedStorage } returns flowOf("/storage/emulated/0/Documents")
    every { libraryFolder.isPublicDocuments(any()) } returns true
    every { resolver.query(any(), any(), any<Bundle>(), any()) } returns null
    val row = Uri.parse("content://media/external_primary/file/42")
    val values = slot<ContentValues>()
    every { resolver.insert(any(), capture(values)) } returns row

    assertEquals("$row", targets.create("wiki.zim"))
    assertEquals("wiki.zim", values.captured.getAsString(MediaColumns.DISPLAY_NAME))
    assertEquals("Documents/Kiwix/", values.captured.getAsString(MediaColumns.RELATIVE_PATH))
    assertEquals(1, values.captured.getAsInteger(MediaColumns.IS_PENDING))
  }

  @Test
  fun pendingRowFromAnEarlierAttemptIsReused() = runTest {
    coEvery { kiwixDataStore.selectedStorage } returns flowOf("/storage/emulated/0/Documents")
    every { libraryFolder.isPublicDocuments(any()) } returns true
    every { resolver.query(any(), any(), any<Bundle>(), any()) } returns
      MatrixCursor(arrayOf(MediaColumns._ID)).apply { addRow(arrayOf<Any>(7L)) }

    assertEquals(
      "${MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)}/7",
      targets.create("wiki.zim")
    )
    verify(exactly = 0) { resolver.insert(any(), any()) }
  }

  @Test
  fun finishedMediaStoreRowIsPublishedAndReadByPath() = runTest {
    val row = Uri.parse("content://media/external_primary/file/42")
    val file = tempFolder.newFile("wiki.zim")
    every { resolver.query(row, any(), null, null, null) } returns
      MatrixCursor(arrayOf(MediaColumns.DATA)).apply { addRow(arrayOf<Any>(file.path)) }
    val values = slot<ContentValues>()
    every { resolver.update(row, capture(values), null, null) } returns 1

    assertEquals(file.path, targets.finish("$row"))
    assertEquals(0, values.captured.getAsInteger(MediaColumns.IS_PENDING))
  }

  @Test
  fun unreadableMediaStoreFileStaysAUri() = runTest {
    val row = Uri.parse("content://media/external_primary/file/43")
    every { resolver.query(row, any(), null, null, null) } returns
      MatrixCursor(arrayOf(MediaColumns.DATA)).apply {
        addRow(arrayOf<Any>(File(tempFolder.root, "missing.zim").path))
      }
    assertEquals("$row", targets.finish("$row"))
  }

  @Test
  fun allFilesAccessWritesDocumentsDirectly() = runTest {
    every { Environment.isExternalStorageManager() } returns true
    coEvery { kiwixDataStore.selectedStorage } returns flowOf("/storage/emulated/0/Documents")
    every { libraryFolder.isPublicDocuments(any()) } returns true
    assertEquals("/storage/emulated/0/Documents/Kiwix/wiki.zim", targets.create("wiki.zim"))
    verify(exactly = 0) { resolver.insert(any(), any()) }
  }
}
