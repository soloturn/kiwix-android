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
package org.kiwix.kiwixmobile.core.reader

import android.webkit.WebResourceResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.kiwix.kiwixmobile.core.di.IoDispatcher
import org.kiwix.kiwixmobile.core.epub.EpubFileReader
import org.kiwix.kiwixmobile.core.reader.ZimFileReader.Factory
import java.io.File
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton

// Coroutine-native reader/writer lock: write() suspends instead of blocking a thread,
// so a writer queued behind a slow reader can't starve a shared dispatcher's pool.
private class ReadWriteMutex {
  private val readerCountMutex = Mutex()
  private val writerMutex = Mutex()
  private var readerCount = 0

  suspend fun <T> read(block: suspend () -> T): T {
    readerCountMutex.withLock {
      if (++readerCount == 1) {
        try {
          writerMutex.lock()
        } catch (cancellation: CancellationException) {
          // Never acquired writerMutex - undo the increment so the count stays
          // balanced, otherwise it can never reach 0 again and writerMutex
          // would appear permanently held to every future writer.
          readerCount--
          throw cancellation
        }
      }
    }
    try {
      return block()
    } finally {
      // Cancelling the caller must not skip this: Mutex.withLock() is a suspend
      // fun, so an already-cancelled coroutine can hit it here and silently
      // no-op, leaking readerCount and stranding writerMutex locked forever.
      withContext(NonCancellable) {
        readerCountMutex.withLock {
          if (--readerCount == 0) writerMutex.unlock()
        }
      }
    }
  }

  suspend fun <T> write(block: suspend () -> T): T = writerMutex.withLock { block() }
}

@Singleton
class ZimReaderContainer @Inject constructor(
  private val zimFileReaderFactory: Factory,
  @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
  // Guards `backingZimFileReader` against the native use-after-free that happens when the
  // setter below disposes the current reader (native Archive/SuggestionSearcher) while the
  // WebView's Chromium worker thread is mid-read via isRedirect/getRedirect/load, called from
  // CoreWebViewClient.shouldInterceptRequest. write() only completes once every in-flight
  // read() has finished, so readers see either the old or the new reader, never a disposed one.
  private val lock = ReadWriteMutex()

  // Volatile so hasReader can read it lock-free below - every other accessor still
  // goes through lock.read {} because it calls into the disposable native Archive.
  @Volatile
  private var backingReader: BookReader? = null

  // Serializes setZimReaderSource() itself so callers (CoreReaderViewModel,
  // DeleteFilesUseCase, ...) can never race to dispose each other's reader
  // mid-open - only one open/close runs at a time, in call order.
  private val setReaderMutex = Mutex()

  private suspend fun <T> withReaderSuspend(block: suspend (ZimFileReader?) -> T): T =
    lock.read { block(backingReader as? ZimFileReader) }

  private suspend fun <T> withBookReaderSuspend(block: suspend (BookReader?) -> T): T =
    lock.read { block(backingReader) }

  // For synchronous callers (WebView's own thread); parks only that caller's thread,
  // not a shared dispatcher.
  private fun <T> withReaderOrNull(block: (ZimFileReader?) -> T): T =
    runBlocking { withReaderSuspend(block) }

  // Format-agnostic view for callers that only need the BookReader surface.
  private fun <T> withBookReaderOrNull(block: (BookReader?) -> T): T =
    runBlocking { withBookReaderSuspend(block) }

  fun <T> withReaderBlocking(block: (ZimFileReader) -> T): T? =
    withReaderOrNull { it?.let(block) }

  /** Coroutine-friendly version of [withReaderBlocking] - hops onto ioDispatcher first. */
  suspend fun <T> withReader(block: (ZimFileReader) -> T): T? =
    withContext(ioDispatcher) { withReaderSuspend { it?.let(block) } }

  // A reference check, not a native call - reading it directly avoids the runBlocking
  // deadlock this hit when polled from Main while a writer was also queued on Main
  // (see setZimReaderSource, which now hops off Main for the same reason).
  val hasReader: Boolean get() = backingReader != null

  suspend fun setZimReaderSource(
    zimReaderSource: ZimReaderSource?,
    showSearchSuggestionsSpellChecked: Boolean = false
  ) = setReaderMutex.withLock {
    // A non-ZIM (EPUB) reader is never "the same" as a ZIM source, including null (closes it).
    val current = withBookReaderSuspend { it }
    if ((current == null || current is ZimFileReader) &&
      zimReaderSource == (current as? ZimFileReader)?.zimReaderSource
    ) {
      return@withLock
    }
    withContext(ioDispatcher) {
      val newReader =
        if (zimReaderSource?.exists(ioDispatcher) == true &&
          zimReaderSource.canOpenInLibkiwix(ioDispatcher)
        ) {
          zimFileReaderFactory.create(zimReaderSource, showSearchSuggestionsSpellChecked)
        } else {
          null
        }
      // Stays on ioDispatcher rather than returning to the caller's (often Main)
      // dispatcher: a reader anywhere can only unblock this by unlocking writerMutex,
      // and that resumption must never depend on the Main looper being free to run it.
      lock.write {
        backingReader?.dispose()
        backingReader = newReader
      }
    }
  }

  /** Opens [file] as an EPUB (or closes any reader when null). False if it can't be parsed. */
  suspend fun setEpubFile(file: File?): Boolean = setReaderMutex.withLock {
    withContext(ioDispatcher) {
      val newReader = file?.let { runCatching { EpubFileReader(it) }.getOrNull() }
      // Parse the lazy ToC here, off Main.
      runCatching { newReader?.toc }
      lock.write {
        backingReader?.dispose()
        backingReader = newReader
      }
      file == null || newReader != null
    }
  }

  /** True when the open book is an EPUB (ZIM-only features are unavailable). */
  val isEpubOpen: Boolean get() = backingReader is EpubFileReader

  /** The open EPUB reader, or null. Lock-free like [isEpubOpen]; its zip reads fail safe once disposed. */
  val epubReader: EpubFileReader? get() = backingReader as? EpubFileReader

  /** Persistable identity of the open book, any format. */
  val sourceId: String? get() = withBookReaderOrNull { it?.sourceId }

  // ZIM-only: null for EPUB (no title index or random entries).
  fun getPageUrlFromTitle(title: String) = withReaderOrNull { it?.getPageUrlFrom(title) }

  fun getRandomPageUrl() = withReaderOrNull { it?.getRandomPageUrl() }
  fun isRedirect(url: String): Boolean = withBookReaderOrNull { it?.isRedirect(url) == true }
  fun getRedirect(url: String): String = withBookReaderOrNull { it?.getRedirect(url) }.orEmpty()
  fun load(url: String, requestHeaders: Map<String, String>): WebResourceResponse = runBlocking {
    return@runBlocking withBookReaderSuspend { reader ->
      val stream = reader?.load(url)
      WebResourceResponse(
        reader?.getMimeTypeFromUrl(url),
        Charsets.UTF_8.name(),
        stream
      )
        .apply {
          val headers = mutableMapOf("Accept-Ranges" to "bytes")
          if (reader is EpubFileReader) {
            // EPUB entries are small and not range-addressable; external or missing => 404.
            if (stream == null) {
              setStatusCodeAndReasonPhrase(HttpURLConnection.HTTP_NOT_FOUND, "Not Found")
            } else {
              setStatusCodeAndReasonPhrase(HttpURLConnection.HTTP_OK, "OK")
            }
            responseHeaders = emptyMap()
          } else if ("Range" in requestHeaders.keys) {
            setStatusCodeAndReasonPhrase(HttpURLConnection.HTTP_PARTIAL, "Partial Content")
            val fullSize = (reader as? ZimFileReader)?.getItem(url)?.itemSize() ?: 0L
            val lastByte = fullSize - 1
            val byteRanges = requestHeaders.getValue("Range").substringAfter("=").split("-")
            headers["Content-Range"] = "bytes ${byteRanges[0]}-$lastByte/$fullSize"
            if (byteRanges.size == 1) {
              headers["Connection"] = "close"
            }
            responseHeaders = headers
          } else {
            setStatusCodeAndReasonPhrase(HttpURLConnection.HTTP_OK, "OK")
            responseHeaders = headers
          }
        }
    }
  }

  val zimReaderSource get() = withReaderOrNull { it?.zimReaderSource }
  val zimFileTitle get() = withBookReaderOrNull { it?.title }
  val mainPage get() = withBookReaderOrNull { it?.mainPage }
  val id get() = withBookReaderOrNull { it?.id }
  val fileSize get() = withReaderOrNull { it?.fileSize } ?: 0L
  val creator get() = withBookReaderOrNull { it?.creator }
  val publisher get() = withBookReaderOrNull { it?.publisher }
  val name get() = withBookReaderOrNull { it?.name }
  val date get() = withBookReaderOrNull { it?.date }
  val description get() = withBookReaderOrNull { it?.description }
  val favicon get() = withBookReaderOrNull { it?.favicon }
  val language get() = withBookReaderOrNull { it?.language }
}
