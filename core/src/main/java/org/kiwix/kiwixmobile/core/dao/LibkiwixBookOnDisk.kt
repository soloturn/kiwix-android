/*
 * Kiwix Android
 * Copyright (c) 2025 Kiwix <android.kiwix.org>
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

package org.kiwix.kiwixmobile.core.dao

import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.provider.DocumentsContract
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.kiwix.kiwixmobile.core.dao.LibkiwixBookmarks.Companion.TAG
import org.kiwix.kiwixmobile.core.di.IoDispatcher
import org.kiwix.kiwixmobile.core.di.modules.LOCAL_BOOKS_LIBRARY
import org.kiwix.kiwixmobile.core.di.modules.LOCAL_BOOKS_MANAGER
import org.kiwix.kiwixmobile.core.entity.LibkiwixBook
import org.kiwix.kiwixmobile.core.extensions.isFileExist
import org.kiwix.kiwixmobile.core.reader.ZimFileReader
import org.kiwix.kiwixmobile.core.reader.ZimReaderSource
import org.kiwix.kiwixmobile.core.utils.StorageDeviceProvider
import org.kiwix.kiwixmobile.core.utils.datastore.KiwixDataStore
import org.kiwix.kiwixmobile.core.utils.files.FileUtils
import org.kiwix.kiwixmobile.core.utils.files.Log
import org.kiwix.kiwixmobile.core.utils.files.UserDataFiles
import org.kiwix.kiwixmobile.core.utils.files.saf.DocumentTree
import org.kiwix.kiwixmobile.core.zim_manager.fileselect_view.BooksOnDiskListItem.BookOnDisk
import org.kiwix.libkiwix.Book
import org.kiwix.libkiwix.Library
import org.kiwix.libkiwix.Manager
import org.kiwix.libzim.Archive
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton

@Suppress("LongParameterList")
@Singleton
class LibkiwixBookOnDisk @Inject constructor(
  @param:Named(LOCAL_BOOKS_LIBRARY) private val library: Library,
  @param:Named(LOCAL_BOOKS_MANAGER) private val manager: Manager,
  private val kiwixDataStore: KiwixDataStore,
  private val storageDeviceProvider: StorageDeviceProvider,
  private val zimFileReaderFactory: ZimFileReader.Factory,
  private val downloadRoomDaoProvider: Provider<DownloadRoomDao>,
  @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
  private val initMutex = Mutex()
  private var isManagerInitialized = false
  private var libraryBooksList: List<String> = arrayListOf()
  private var localBooksList: List<LibkiwixBook> = arrayListOf()

  /**
   * Request new data from Libkiwix when changes occur inside it; otherwise,
   * return the previous data to avoid unnecessary data load on Libkiwix.
   */
  private var booksChanged: Boolean = false

  private val _bookRemoved = MutableSharedFlow<Unit>(
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
  )

  /**
   * A flow that emits a signal whenever a book is removed from the library, allowing observers
   * to react to book removal events. See HotspotService for an example of how
   * to use this flow to update the server when a book is removed.
   */
  val bookRemoved: SharedFlow<Unit> = _bookRemoved.asSharedFlow()

  private val fileObservers = ConcurrentHashMap<String, FileObserver>()

  private val fileSystemEventChannel =
    Channel<suspend () -> Unit>(
      capacity = FILE_SYSTEM_EVENT_BUFFER_CAPACITY,
      onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

  init {
    CoroutineScope(ioDispatcher).launch {
      runCatching { registerFileObservers() }.onFailure { it.printStackTrace() }
      fileSystemEventChannel.receiveAsFlow().collect { event ->
        runCatching {
          event.invoke()
        }.onFailure { it.printStackTrace() }
      }
    }
  }

  private suspend fun registerFileObservers() {
    val directoriesToWatch = storageDeviceProvider.getAppSpecificDirs()
      .asSequence()
      .flatMap { rootDir -> rootDir.walkTopDown().filter(File::isDirectory) }
      .distinctBy(File::getAbsolutePath)

    directoriesToWatch.forEach(::watchDirectory)
  }

  private fun watchDirectory(directory: File) {
    val path = runCatching { directory.canonicalPath }.getOrDefault(directory.absolutePath)
    if (fileObservers.containsKey(path)) return
    runCatching {
      val observer = createFileObserver(directory)
      if (fileObservers.putIfAbsent(path, observer) == null) {
        observer.startWatching()
      }
    }.onFailure { it.printStackTrace() }
  }

  private fun unwatchDirectory(directory: File) {
    val path = runCatching { directory.canonicalPath }.getOrDefault(directory.absolutePath)
    fileObservers.remove(path)?.stopWatching()
  }

  private fun createFileObserver(watchDir: File): FileObserver {
    val mask =
      FileObserver.CREATE or FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or
        FileObserver.DELETE or FileObserver.MOVED_FROM
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      object : FileObserver(watchDir, mask) {
        override fun onEvent(event: Int, path: String?) {
          path?.let { handleFileSystemEvent(watchDir, it, event) }
        }
      }
    } else {
      @Suppress("DEPRECATION")
      object : FileObserver(watchDir.path, mask) {
        override fun onEvent(event: Int, path: String?) {
          path?.let { handleFileSystemEvent(watchDir, it, event) }
        }
      }
    }
  }

  private fun handleFileSystemEvent(watchDir: File, path: String, event: Int) {
    fileSystemEventChannel.trySend {
      val file = File(watchDir, path)
      when (event) {
        FileObserver.CREATE -> if (file.isDirectory) watchDirectory(file)
        FileObserver.MOVED_TO -> handleMovedIn(file)
        FileObserver.CLOSE_WRITE -> addZimFileIfValid(file)
        FileObserver.DELETE, FileObserver.MOVED_FROM -> {
          unwatchDirectory(file)
          if (isZimFile(path)) deleteByPath(file.canonicalPath)
        }
      }
    }
  }

  private suspend fun handleMovedIn(file: File) {
    if (file.isDirectory) {
      file.walkTopDown().filter(File::isDirectory).forEach(::watchDirectory)
      file.walkTopDown().filter { isZimFile(it.name) }.forEach { addBookFromFile(it) }
    } else {
      addZimFileIfValid(file)
    }
  }

  private fun isZimFile(name: String) =
    FileUtils.isValidZimFile(name) || FileUtils.isSplittedZimFile(name)

  private suspend fun addZimFileIfValid(file: File) {
    if (isZimFile(file.name) && !isBeingDownloaded(file)) addBookFromFile(file)
  }

  private fun isBeingDownloaded(file: File): Boolean =
    downloadRoomDaoProvider.get().getActiveDownloadForFileName(file.name) != null

  private suspend fun addBookFromFile(file: File) {
    if (!file.isFileExist(ioDispatcher) || !file.isFile) return
    zimFileReaderFactory.create(ZimReaderSource(file), false)?.let { zimFileReader ->
      try {
        insert(listOf(Book().apply { update(zimFileReader.jniKiwixReader) }))
      } finally {
        zimFileReader.dispose()
      }
    }
  }

  suspend fun deleteByPath(filePath: String) {
    val normalizedPath = runCatching { File(filePath).canonicalPath }.getOrDefault(filePath)
    getBooksList().firstOrNull { book ->
      runCatching {
        File(book.zimReaderSource.toDatabase()).canonicalPath == normalizedPath
      }.getOrDefault(false)
    }?.let { delete(it.id) }
  }

  private val uriBookIndex by lazy {
    UriBookIndex(
      UserDataFiles.internalFile(
        kiwixDataStore.context,
        UserDataFiles.LOCAL_LIBRARY_DIR,
        UriBookIndex.FILE_NAME
      )
    )
  }

  private fun libraryFile(): File =
    UserDataFiles.internalFile(
      kiwixDataStore.context,
      UserDataFiles.LOCAL_LIBRARY_DIR,
      UserDataFiles.LIBRARY_FILE
    )

  private suspend fun ensureInitialized() {
    if (isManagerInitialized) return
    initMutex.withLock {
      if (isManagerInitialized) return@withLock true
      withContext(ioDispatcher) {
        val libraryFile = libraryFile()
        val legacyLibrary = UserDataFiles.migrationSource(
          kiwixDataStore.context,
          UserDataFiles.LOCAL_LIBRARY_DIR,
          UserDataFiles.LIBRARY_FILE
        )
        val folder = libraryFile.parentFile
        folder?.mkdirs()
        check(folder?.exists() == true) { "Could not create library folder: ${folder?.path}" }
        if (legacyLibrary != null) {
          // Read through libkiwix rather than copying: book paths are stored relative to the
          // library file, so a byte copy would point them at the wrong place.
          manager.readFile(legacyLibrary.canonicalPath)
          library.writeToFile(libraryFile.canonicalPath)
        } else {
          if (!libraryFile.isFileExist(ioDispatcher)) libraryFile.createNewFile()
          check(libraryFile.exists()) { "Could not create library file: ${libraryFile.path}" }
          manager.readFile(libraryFile.canonicalPath)
        }
        isManagerInitialized = true
      }
    }
  }

  private val localBooksFlow: MutableStateFlow<List<LibkiwixBook>?> by lazy {
    MutableStateFlow<List<LibkiwixBook>?>(null).also { flow ->
      CoroutineScope(ioDispatcher).launch {
        runCatching {
          flow.emit(getBooksList())
        }.onFailure { it.printStackTrace() }
      }
    }
  }

  private suspend fun getBooksList(): List<LibkiwixBook> =
    withContext(ioDispatcher) {
      // if reading library failed, return empty list
      ensureInitialized()
      if (!booksChanged && localBooksList.isNotEmpty()) {
        // No changes, return the cached data
        return@withContext localBooksList.distinctBy(LibkiwixBook::path)
      }
      // Retrieve the list of books from the library.
      val booksIds = library.booksIds.toList()
      libraryBooksList = booksIds
      // Create a list to store LibkiwixBook objects.
      val libkiwixBooks =
        booksIds.mapNotNull { bookId ->
          val book = library.getBookById(bookId)
          return@mapNotNull LibkiwixBook(book)
        }
      val libkiwixIds = booksIds.toSet()
      localBooksList = libkiwixBooks + uriBookIndex.all().filterNot { it.id in libkiwixIds }
      // set the books change to false to avoid reloading the data from libkiwix
      booksChanged = false

      return@withContext localBooksList.distinctBy(LibkiwixBook::path)
    }

  /**
   * Adds a book that can only be read through [uri] (a document in the user's SAF library
   * folder, or a MediaStore row). Returns the stored book, or null if [uri] is not a valid ZIM.
   */
  suspend fun insertUriBook(uri: Uri): LibkiwixBook? = withContext(ioDispatcher) {
    ensureInitialized()
    val book = readUriBook(uri) ?: return@withContext null
    val sameBookAsFile = book.id.takeIf { it in library.booksIds }
      ?.let(library::getBookById)
      ?.let(::LibkiwixBook)
      ?.takeIf { it.zimReaderSource.exists(ioDispatcher) }
    // Direct file access is cheaper than a descriptor per book, so a readable file entry wins.
    sameBookAsFile ?: book.also {
      uriBookIndex.put(it)
      booksChanged = true
      updateLocalBooksFlow()
    }
  }

  private suspend fun readUriBook(uri: Uri): LibkiwixBook? {
    val source = ZimReaderSource(uri)
    return runCatching {
      source.createArchive(ioDispatcher)?.let { archive ->
        try {
          LibkiwixBook(Book().apply { update(archive) }).apply { path = "$uri" }
        } finally {
          archive.dispose()
        }
      }
    }.onFailure { Log.e(TAG, "Could not read $uri: $it") }
      .getOrNull()
      .also { source.releaseDescriptors() }
  }

  /** Adds the ZIM at [path], or at [path] as content:// URI, to the library. */
  suspend fun insertLocation(path: String) {
    if (path.startsWith("content://")) {
      insertUriBook(path.toUri())
    } else {
      withContext(ioDispatcher) {
        val archive = Archive(path)
        try {
          insert(listOf(Book().apply { update(archive) }))
        } finally {
          archive.dispose()
        }
      }
    }
  }

  /** Deletes the document(s) behind a URI book, including split parts, then forgets it. */
  suspend fun deleteUriBook(bookId: String, uri: Uri): Boolean = withContext(ioDispatcher) {
    val resolver = kiwixDataStore.context.contentResolver
    val tree = DocumentTree(resolver)
    val deleted = if (DocumentTree.isDocumentInTree(uri)) {
      tree.splitZimParts(uri).map(tree::delete).all { it }
    } else {
      runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
    }
    if (deleted) delete(bookId)
    deleted
  }

  /**
   * URI books are only dropped when we still hold access to their folder. Without a grant
   * (e.g. after reinstall, before the user re-picks the folder) they are kept so that a
   * restore brings bookmarks and notes back to the same books.
   */
  private fun canVerifyExistence(book: LibkiwixBook): Boolean {
    val uri = book.zimReaderSource.uri?.takeIf(DocumentTree::isDocumentInTree) ?: return true
    val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
    return kiwixDataStore.context.contentResolver.persistedUriPermissions.any { permission ->
      permission.uri.authority == uri.authority &&
        runCatching { DocumentsContract.getTreeDocumentId(permission.uri) }.getOrNull() == treeId
    }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  fun books() =
    localBooksFlow
      .filterNotNull()
      .mapLatest { booksList ->
        removeBooksThatAreInTrashFolder(booksList)
        removeBooksThatDoNotExist(booksList.toMutableList())
        booksList.mapNotNull { book ->
          try {
            if (book.zimReaderSource.exists(ioDispatcher) &&
              !isInTrashFolder(book.zimReaderSource.toDatabase())
            ) {
              book
            } else {
              null
            }
          } catch (_: Exception) {
            null
          }
        }
      }
      .map { it.map(::BookOnDisk) }
      .flowOn(ioDispatcher)

  suspend fun getBooks() = getBooksList().map(::BookOnDisk)

  suspend fun insert(libkiwixBooks: List<Book>) {
    withContext(ioDispatcher) {
      ensureInitialized()
      val existingBookIds = library.booksIds.toSet()
      val existingBookPaths = existingBookIds
        .mapNotNull { id -> library.getBookById(id)?.path }
        .toSet()

      val newBooks = libkiwixBooks.filterNot { book ->
        book.id in existingBookIds ||
          book.path in existingBookPaths ||
          uriBookIndex.contains(book.id)
      }
      newBooks.forEach { book ->
        runCatching {
          addBookToLibraryIfNotExist(book)
        }.onFailure {
          Log.e(TAG, "Failed to add book: ${book.title} - ${it.message}")
        }
      }

      if (newBooks.isNotEmpty()) {
        writeBookMarksAndSaveLibraryToFile()
        updateLocalBooksFlow()
      }
    }
  }

  private fun addBookToLibraryIfNotExist(libKiwixBook: Book?) {
    libKiwixBook?.let { book ->
      if (!isBookAlreadyExistInLibrary(book.id)) {
        library.addBook(libKiwixBook).also {
          // now library has changed so update our library list.
          libraryBooksList = library.booksIds.toList()
          Log.e(
            TAG,
            "Added Book to Library:\n" +
              "ZIM File Path: ${book.path}\n" +
              "Book ID: ${book.id}\n" +
              "Book Title: ${book.title}"
          )
        }
      }
    }
  }

  private fun isBookAlreadyExistInLibrary(bookId: String): Boolean {
    if (libraryBooksList.isEmpty()) {
      // store booksIds in a list to avoid multiple data call on libkiwix
      libraryBooksList = library.booksIds.toList()
    }
    return libraryBooksList.any { it == bookId }
  }

  private suspend fun removeBooksThatDoNotExist(
    books: MutableList<LibkiwixBook>
  ) {
    delete(books.filter { canVerifyExistence(it) && !it.zimReaderSource.exists(ioDispatcher) })
  }

  // Remove the existing books from database which are showing on the library screen.
  private suspend fun removeBooksThatAreInTrashFolder(books: List<LibkiwixBook>) {
    delete(books.filter { isInTrashFolder(it.zimReaderSource.toDatabase()) })
  }

  // Check if any existing ZIM file showing on the library screen which is inside the trash folder.
  private suspend fun isInTrashFolder(filePath: String) =
    Regex("/\\.Trash/").containsMatchIn(filePath)

  suspend fun delete(books: List<LibkiwixBook>) {
    if (books.isEmpty()) return
    runCatching {
      ensureInitialized()
      books.forEach {
        library.removeBookById(it.id)
      }
      uriBookIndex.remove(books.map(LibkiwixBook::id))
    }.onFailure { it.printStackTrace() }
    writeBookMarksAndSaveLibraryToFile()
    updateLocalBooksFlow()
    _bookRemoved.tryEmit(Unit)
  }

  suspend fun delete(bookId: String) {
    runCatching {
      ensureInitialized()
      library.removeBookById(bookId)
      uriBookIndex.remove(listOf(bookId))
      writeBookMarksAndSaveLibraryToFile()
      updateLocalBooksFlow()
      _bookRemoved.tryEmit(Unit)
    }.onFailure { it.printStackTrace() }
  }

  suspend fun bookMatching(downloadTitle: String) =
    getBooks().firstOrNull {
      it.zimReaderSource.toDatabase().endsWith(downloadTitle, true)
    }

  suspend fun bookById(bookId: String) =
    getBooks().firstOrNull { it.book.id == bookId }

  /**
   * Asynchronously writes the library data to their respective file in a background thread
   * to prevent potential data loss and ensures that the library holds the updated ZIM file data.
   */
  private suspend fun writeBookMarksAndSaveLibraryToFile() {
    ensureInitialized()
    // Save the library, which contains ZIM file data.
    library.writeToFile(libraryFile().canonicalPath)
    // set the bookmark change to true so that libkiwix will return the new data.
    booksChanged = true
  }

  private suspend fun updateLocalBooksFlow() {
    localBooksFlow.emit(getBooksList())
  }

  companion object {
    private const val FILE_SYSTEM_EVENT_BUFFER_CAPACITY = 64
  }
}
