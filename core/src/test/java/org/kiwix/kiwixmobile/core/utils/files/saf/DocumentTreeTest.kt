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
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kiwix.sharedFunctions.TestApplication
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R], application = TestApplication::class)
class DocumentTreeTest {
  private val authority = "com.android.externalstorage.documents"
  private val rootId = "primary:Documents/Kiwix"
  private val treeUri: Uri = DocumentsContract.buildTreeDocumentUri(authority, rootId)
  private val resolver: ContentResolver = mockk(relaxed = true)
  private val queries = mutableListOf<Uri>()

  /** Directory id -> children as (name, mimeType, size). */
  private val folders = mapOf(
    rootId to listOf(
      Triple("wiki.zim", "application/octet-stream", 10L),
      Triple("notes.txt", "text/plain", 1L),
      Triple("split.zimaa", "application/octet-stream", 4L),
      Triple("split.zimab", "application/octet-stream", 4L),
      Triple("split.zimac", "application/octet-stream", 2L),
      Triple("download.zim.part", "application/octet-stream", 3L),
      Triple("sub", Document.MIME_TYPE_DIR, 0L),
      Triple(".Trash", Document.MIME_TYPE_DIR, 0L)
    ),
    "$rootId/sub" to listOf(Triple("nested.zim", "application/octet-stream", 5L)),
    "$rootId/.Trash" to listOf(Triple("deleted.zim", "application/octet-stream", 5L))
  )

  @Before
  fun setUp() {
    every { resolver.query(any(), any(), any(), any(), any()) } answers {
      val uri = firstArg<Uri>()
      queries += uri
      val parentId = DocumentsContract.getDocumentId(uri)
      MatrixCursor(
        arrayOf(
          Document.COLUMN_DOCUMENT_ID,
          Document.COLUMN_DISPLAY_NAME,
          Document.COLUMN_MIME_TYPE,
          Document.COLUMN_SIZE
        )
      ).apply {
        folders[parentId].orEmpty().forEach { (name, mime, size) ->
          addRow(arrayOf<Any>("$parentId/$name", name, mime, size))
        }
      }
    }
  }

  @Test
  fun listsADirectoryWithASingleQuery() {
    val children = DocumentTree(resolver).listChildren(treeUri)
    assertEquals(8, children.size)
    assertEquals(1, queries.size)
    assertEquals(
      DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId),
      queries.single()
    )
    val wiki = children.first { it.name == "wiki.zim" }
    assertEquals(10L, wiki.size)
    assertEquals(DocumentsContract.buildDocumentUriUsingTree(treeUri, "$rootId/wiki.zim"), wiki.uri)
  }

  @Test
  fun findsZimFilesRecursivelyButSkipsHiddenFoldersAndPartials() {
    val names = DocumentTree(resolver).findZimFiles(treeUri).map { it.name }.toSet()
    assertEquals(setOf("wiki.zim", "split.zimaa", "nested.zim"), names)
    // root + sub; .Trash is never listed
    assertEquals(2, queries.size)
  }

  @Test
  fun splitPartsAreFoundAsSiblingsInOrder() {
    val firstPart = DocumentsContract.buildDocumentUriUsingTree(treeUri, "$rootId/split.zimaa")
    val parts = DocumentTree(resolver).splitZimParts(firstPart)
    assertEquals(
      listOf("split.zimaa", "split.zimab", "split.zimac").map {
        DocumentsContract.buildDocumentUriUsingTree(treeUri, "$rootId/$it")
      },
      parts
    )
  }

  @Test
  fun unsplitDocumentIsItsOwnOnlyPart() {
    val wiki = DocumentsContract.buildDocumentUriUsingTree(treeUri, "$rootId/wiki.zim")
    assertEquals(listOf(wiki), DocumentTree(resolver).splitZimParts(wiki))
    val foreign = Uri.parse("content://media/external/file/12")
    assertEquals(listOf(foreign), DocumentTree(resolver).splitZimParts(foreign))
    verify(exactly = 1) { resolver.query(any(), any(), any(), any(), any()) }
  }

  @Test
  fun recognisesTreeDocumentUris() {
    val document = DocumentsContract.buildDocumentUriUsingTree(treeUri, "$rootId/wiki.zim")
    assertTrue(DocumentTree.isDocumentInTree(document))
    assertFalse(DocumentTree.isDocumentInTree(treeUri))
    assertFalse(DocumentTree.isDocumentInTree(Uri.parse("content://media/external/file/1")))
    assertEquals(treeUri, DocumentTree.treeUriOf(document))
  }
}
