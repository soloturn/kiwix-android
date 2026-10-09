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
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import org.kiwix.kiwixmobile.core.utils.files.FileUtils
import org.kiwix.kiwixmobile.core.utils.files.Log

/** One row of a [DocumentsContract] child query. */
data class DocumentEntry(
  val uri: Uri,
  val documentId: String,
  val parentDocumentId: String,
  val name: String,
  val mimeType: String?,
  val size: Long
) {
  val isDirectory: Boolean get() = mimeType == Document.MIME_TYPE_DIR
}

/**
 * Storage Access Framework tree access. Every directory is listed with a single
 * [ContentResolver.query] on its children URI; DocumentFile.listFiles()/findFile() issue one
 * Binder call per child, which is too slow for folders holding many large ZIM files.
 */
@Suppress("TooManyFunctions")
class DocumentTree(private val contentResolver: ContentResolver) {
  fun rootDocumentId(treeUri: Uri): String = DocumentsContract.getTreeDocumentId(treeUri)

  fun documentUri(treeUri: Uri, documentId: String): Uri =
    DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

  fun listChildren(
    treeUri: Uri,
    parentDocumentId: String = rootDocumentId(treeUri)
  ): List<DocumentEntry> {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
    return runCatching {
      contentResolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
        buildList {
          while (cursor.moveToNext()) {
            cursor.toEntry(parentDocumentId) { documentUri(treeUri, it) }?.let(::add)
          }
        }
      }.orEmpty()
    }.onFailure { Log.e(TAG, "Could not list $childrenUri: $it") }.getOrDefault(emptyList())
  }

  /** Breadth-first walk returning every ZIM (or first split part) below the tree root. */
  fun findZimFiles(treeUri: Uri, onDirectoryScanned: (Int) -> Unit = {}): List<DocumentEntry> {
    val result = mutableListOf<DocumentEntry>()
    val pending = ArrayDeque<String>().apply { add(rootDocumentId(treeUri)) }
    val visited = mutableSetOf<String>()
    while (pending.isNotEmpty()) {
      val directoryId = pending.removeFirst()
      if (!visited.add(directoryId)) continue
      val children = listChildren(treeUri, directoryId)
      children.filter { it.isDirectory && !isSkippedDirectory(it.name) }
        .forEach { pending.add(it.documentId) }
      result += children.filter { !it.isDirectory && isZimName(it.name) }
      onDirectoryScanned(visited.size)
    }
    return result
  }

  fun findChild(treeUri: Uri, parentDocumentId: String, name: String): DocumentEntry? =
    listChildren(treeUri, parentDocumentId).firstOrNull { it.name == name }

  fun createDocument(
    treeUri: Uri,
    name: String,
    mimeType: String = ZIM_MIME_TYPE,
    parentDocumentId: String = rootDocumentId(treeUri)
  ): Uri? = runCatching {
    DocumentsContract.createDocument(
      contentResolver,
      documentUri(treeUri, parentDocumentId),
      mimeType,
      name
    )
  }.onFailure { Log.e(TAG, "Could not create $name in $treeUri: $it") }.getOrNull()

  fun rename(documentUri: Uri, newName: String): Uri? = runCatching {
    DocumentsContract.renameDocument(contentResolver, documentUri, newName)
  }.onFailure { Log.e(TAG, "Could not rename $documentUri: $it") }.getOrNull()

  fun delete(documentUri: Uri): Boolean = runCatching {
    DocumentsContract.deleteDocument(contentResolver, documentUri)
  }.getOrDefault(false)

  /** Returns name and size of a single document, or null if it is gone or inaccessible. */
  fun stat(documentUri: Uri): DocumentEntry? = runCatching {
    contentResolver.query(documentUri, PROJECTION, null, null, null)?.use { cursor ->
      cursor.takeIf(Cursor::moveToFirst)?.toEntry("") { documentUri }
    }
  }.getOrNull()

  /**
   * All parts of a split ZIM ("x.zimaa", "x.zimab", ...) that sit next to [firstPart], in order.
   * Siblings are found with one query on the parent directory.
   */
  fun splitZimParts(firstPart: Uri): List<Uri> =
    splitZimSiblings(firstPart)?.takeIf(List<Uri>::isNotEmpty) ?: listOf(firstPart)

  /** Folder beside [zimUri] for files saved from inside that zim; created when [create] and absent. */
  @Suppress("ReturnCount")
  fun downloadsDirectory(zimUri: Uri, name: String, create: Boolean = false): DocumentEntry? {
    if (!isDocumentInTree(zimUri)) return null
    val treeUri = treeUriOf(zimUri)
    val zimDocumentId = DocumentsContract.getDocumentId(zimUri)
    val parentId = parentDocumentId(treeUri, zimUri, zimDocumentId) ?: return null
    findChild(treeUri, parentId, name)?.takeIf { it.isDirectory }?.let { return it }
    if (!create) return null
    val uri = createDocument(treeUri, name, Document.MIME_TYPE_DIR, parentId) ?: return null
    return DocumentEntry(
      uri = uri,
      documentId = DocumentsContract.getDocumentId(uri),
      parentDocumentId = parentId,
      name = name,
      mimeType = Document.MIME_TYPE_DIR,
      size = 0L
    )
  }

  fun deleteDownloadsDirectory(zimUri: Uri, name: String): Boolean =
    downloadsDirectory(zimUri, name)?.let { delete(it.uri) } ?: true

  private fun splitZimSiblings(firstPart: Uri): List<Uri>? {
    if (!isDocumentInTree(firstPart)) return null
    val treeUri = treeUriOf(firstPart)
    val documentId = DocumentsContract.getDocumentId(firstPart)
    val siblings = parentDocumentId(treeUri, firstPart, documentId)
      ?.let { listChildren(treeUri, it) }
      .orEmpty()
    val prefix = siblings.firstOrNull { it.documentId == documentId }?.name
      ?.takeIf(FileUtils::isSplittedZimFile)
      ?.dropLast(SPLIT_SUFFIX_LENGTH)
    return siblings
      .filter { prefix != null && it.name.startsWith(prefix) && isSplitSuffix(it.name, prefix) }
      .sortedBy { it.name }
      .map { it.uri }
  }

  private fun isSplitSuffix(name: String, prefix: String) =
    SPLIT_SUFFIX.matches(name.removePrefix(prefix))

  private fun Cursor.toEntry(parentDocumentId: String, uriFor: (String) -> Uri): DocumentEntry? {
    val documentId = getString(COLUMN_ID) ?: return null
    return DocumentEntry(
      uri = uriFor(documentId),
      documentId = documentId,
      parentDocumentId = parentDocumentId,
      name = getString(COLUMN_NAME).orEmpty(),
      mimeType = getString(COLUMN_MIME),
      size = if (isNull(COLUMN_SIZE)) 0L else getLong(COLUMN_SIZE)
    )
  }

  private fun parentDocumentId(treeUri: Uri, documentUri: Uri, documentId: String): String? =
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        DocumentsContract.findDocumentPath(contentResolver, documentUri)?.path
          ?.takeIf { it.size >= 2 }
          ?.let { it[it.size - 2] }
      } else {
        null
      }
    }.getOrNull()
      ?: documentId.substringBeforeLast('/', "").takeIf(String::isNotEmpty)
      ?: rootDocumentId(treeUri).takeIf { it != documentId }

  companion object {
    private const val TAG = "DocumentTree"
    const val ZIM_MIME_TYPE = "application/octet-stream"
    const val PARTIAL_SUFFIX = ".part"
    private val SPLIT_SUFFIX = Regex("[a-z]{2}")
    private const val SPLIT_SUFFIX_LENGTH = 2
    private const val MIN_TREE_DOCUMENT_SEGMENTS = 4
    private const val COLUMN_ID = 0
    private const val COLUMN_NAME = 1
    private const val COLUMN_MIME = 2
    private const val COLUMN_SIZE = 3
    private val PROJECTION = arrayOf(
      Document.COLUMN_DOCUMENT_ID,
      Document.COLUMN_DISPLAY_NAME,
      Document.COLUMN_MIME_TYPE,
      Document.COLUMN_SIZE
    )

    fun isZimName(name: String): Boolean =
      name.endsWith(".zim", ignoreCase = true) || name.endsWith(".zimaa", ignoreCase = true)

    // Mirrors FileSearch: hidden folders and the trash never hold library books.
    private fun isSkippedDirectory(name: String): Boolean =
      name.startsWith(".") || name.equals("data", true) || name.equals("obb", true)

    fun isDocumentInTree(uri: Uri): Boolean =
      uri.scheme == ContentResolver.SCHEME_CONTENT &&
        uri.pathSegments.size >= MIN_TREE_DOCUMENT_SEGMENTS &&
        uri.pathSegments[0] == "tree" &&
        uri.pathSegments[2] == "document"

    fun treeUriOf(documentUri: Uri): Uri =
      DocumentsContract.buildTreeDocumentUri(
        documentUri.authority,
        DocumentsContract.getTreeDocumentId(documentUri)
      )
  }
}
