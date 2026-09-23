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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.kiwix.kiwixmobile.core.di.IoDispatcher
import org.kiwix.kiwixmobile.core.reader.ZimFileReader.Factory
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
      if (++readerCount == 1) writerMutex.lock()
    }
    try {
      return block()
    } finally {
      readerCountMutex.withLock {
        if (--readerCount == 0) writerMutex.unlock()
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
  private var backingZimFileReader: ZimFileReader? = null

  // Serializes setZimReaderSource() itself so callers (CoreReaderViewModel,
  // DeleteFilesUseCase, ...) can never race to dispose each other's reader
  // mid-open - only one open/close runs at a time, in call order.
  private val setReaderMutex = Mutex()

  private suspend fun <T> withReaderSuspend(block: suspend (ZimFileReader?) -> T): T =
    lock.read { block(backingZimFileReader) }

  // For synchronous callers (WebView's own thread); parks only that caller's thread,
  // not a shared dispatcher.
  private fun <T> withReaderOrNull(block: (ZimFileReader?) -> T): T =
    runBlocking { withReaderSuspend(block) }

  fun <T> withReaderBlocking(block: (ZimFileReader) -> T): T? =
    withReaderOrNull { it?.let(block) }

  /** Coroutine-friendly version of [withReaderBlocking] - hops onto ioDispatcher first. */
  suspend fun <T> withReader(block: (ZimFileReader) -> T): T? =
    withContext(ioDispatcher) { withReaderSuspend { it?.let(block) } }

  val hasReader: Boolean get() = withReaderOrNull { it != null }

  suspend fun setZimReaderSource(
    zimReaderSource: ZimReaderSource?,
    showSearchSuggestionsSpellChecked: Boolean = false
  ) = setReaderMutex.withLock {
    if (zimReaderSource == withReaderSuspend { it?.zimReaderSource }) {
      return@withLock
    }
    val newReader = withContext(ioDispatcher) {
      if (zimReaderSource?.exists(ioDispatcher) == true &&
        zimReaderSource.canOpenInLibkiwix(ioDispatcher)
      ) {
        zimFileReaderFactory.create(zimReaderSource, showSearchSuggestionsSpellChecked)
      } else {
        null
      }
    }
    lock.write {
      backingZimFileReader?.dispose()
      backingZimFileReader = newReader
    }
  }

  fun getPageUrlFromTitle(title: String) = withReaderOrNull { it?.getPageUrlFrom(title) }

  fun getRandomPageUrl() = withReaderOrNull { it?.getRandomPageUrl() }
  fun isRedirect(url: String): Boolean = withReaderOrNull { it?.isRedirect(url) == true }
  fun getRedirect(url: String): String = withReaderOrNull { it?.getRedirect(url) }.orEmpty()
  fun load(url: String, requestHeaders: Map<String, String>): WebResourceResponse = runBlocking {
    return@runBlocking withReaderSuspend { reader ->
      WebResourceResponse(
        reader?.getMimeTypeFromUrl(url),
        Charsets.UTF_8.name(),
        reader?.load(url)
      )
        .apply {
          val headers = mutableMapOf("Accept-Ranges" to "bytes")
          if ("Range" in requestHeaders.keys) {
            setStatusCodeAndReasonPhrase(HttpURLConnection.HTTP_PARTIAL, "Partial Content")
            val fullSize = reader?.getItem(url)?.itemSize() ?: 0L
            val lastByte = fullSize - 1
            val byteRanges = requestHeaders.getValue("Range").substringAfter("=").split("-")
            headers["Content-Range"] = "bytes ${byteRanges[0]}-$lastByte/$fullSize"
            if (byteRanges.size == 1) {
              headers["Connection"] = "close"
            }
          } else {
            setStatusCodeAndReasonPhrase(HttpURLConnection.HTTP_OK, "OK")
          }
          responseHeaders = headers
        }
    }
  }

  val zimReaderSource get() = withReaderOrNull { it?.zimReaderSource }
  val zimFileTitle get() = withReaderOrNull { it?.title }
  val mainPage get() = withReaderOrNull { it?.mainPage }
  val id get() = withReaderOrNull { it?.id }
  val fileSize get() = withReaderOrNull { it?.fileSize } ?: 0L
  val creator get() = withReaderOrNull { it?.creator }
  val publisher get() = withReaderOrNull { it?.publisher }
  val name get() = withReaderOrNull { it?.name }
  val date get() = withReaderOrNull { it?.date }
  val description get() = withReaderOrNull { it?.description }
  val favicon get() = withReaderOrNull { it?.favicon }
  val language get() = withReaderOrNull { it?.language }
}
