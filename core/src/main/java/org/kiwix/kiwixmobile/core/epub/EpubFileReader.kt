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

package org.kiwix.kiwixmobile.core.epub

import android.util.Base64
import org.kiwix.kiwixmobile.core.reader.BookReader
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Serves an EPUB's contents under `https://kiwix.app/<zip entry path>`.
 * Method names mirror ZimFileReader so a shared reader interface can wrap both.
 */
@Suppress("ReturnCount", "TooManyFunctions")
class EpubFileReader(private val zip: ZipFile) : BookReader {
  constructor(file: File) : this(ZipFile(file))

  @Volatile private var disposed = false

  // Only well-formed, non-directory entries are addressable; "../x" or absolute names never match.
  private val entries: Map<String, String> = zip.entries().asSequence()
    .filter { !it.isDirectory }
    .mapNotNull { e -> EpubPaths.normalize(e.name)?.takeIf { it == e.name }?.let { it to e.name } }
    .toMap()

  // Case-insensitive fallback, only for names that are unambiguous.
  private val lowerCased: Map<String, String> = entries.keys.groupBy { it.lowercase(Locale.ROOT) }
    .filterValues { it.size == 1 }.mapValues { it.value.single() }

  private val opener: EntryOpener = { path -> openEntry(path) }

  val epubPackage: EpubPackage = EpubParser.parsePackage(opener)

  val metadata: EpubMetadata get() = epubPackage.metadata

  override val sourceId: String get() = zip.name
  override val id: String get() = metadata.identifier ?: sourceId
  override val title: String get() = metadata.title.ifEmpty { File(zip.name).nameWithoutExtension }
  override val name: String get() = title
  override val creator: String get() = metadata.creators.joinToString(", ")
  override val publisher: String get() = metadata.publisher
  override val date: String get() = metadata.date
  override val description: String get() = metadata.description
  override val language: String get() = metadata.language.orEmpty()

  /** Encoded path of the first spine page, relative to https://kiwix.app/ (like a ZIM path). */
  override val mainPage: String? get() = mainPageUrl?.removePrefix(EpubPaths.CONTENT_PREFIX)

  /** Base64 cover image, null if none or larger than [MAX_FAVICON_BYTES]. */
  override val favicon: String?
    get() = runCatching {
      val bytes = metadata.coverPath?.let { openEntry(it) }?.use { it.readBytes() }
      bytes?.takeIf { it.size <= MAX_FAVICON_BYTES }?.let { Base64.encodeToString(it, Base64.DEFAULT) }
    }.getOrNull()

  val toc: List<TocEntry> by lazy { EpubParser.parseToc(epubPackage, opener) }

  val spineUrls: List<String> get() = epubPackage.spine.map { EpubPaths.toUrl(it.item.path) }

  val mainPageUrl: String?
    get() = epubPackage.firstLinear?.let { EpubPaths.toUrl(it.path) }

  val coverUrl: String?
    get() = metadata.coverPath?.let { actualEntry(it) }?.let { EpubPaths.toUrl(it) }

  /** Stream for an internal URL, or null for external, missing, unsafe or after [dispose]. */
  fun open(url: String): InputStream? {
    if (disposed) return null
    val path = EpubPaths.entryPathFromUrl(url) ?: return null
    return openEntry(path)
  }

  // Zip reads are cheap and callers (WebView intercept thread) are already off Main.
  override suspend fun load(uri: String): InputStream? = open(uri)

  // EPUB URLs are served as-is; there are no ZIM-style redirects.
  override fun isRedirect(url: String): Boolean = false

  override fun getRedirect(url: String): String = url

  override fun getMimeTypeFromUrl(uri: String): String? = mimeTypeOf(uri)

  private fun mimeTypeOf(url: String): String? {
    val path = EpubPaths.entryPathFromUrl(url) ?: return null
    val actual = actualEntry(path) ?: return null
    return epubPackage.itemForPath(actual)?.mediaType?.takeIf { it.isNotBlank() }
      ?: EpubMimeTypes.fromPath(actual)
  }

  /** True if [url] points inside this EPUB (https://kiwix.app/ and an existing entry). */
  fun isInternalUrl(url: String): Boolean =
    EpubPaths.entryPathFromUrl(url)?.let { actualEntry(it) } != null

  /** The WebView must block anything that is not https://kiwix.app/. */
  fun isExternalUrl(url: String): Boolean = !EpubPaths.isInternalUrl(url)

  /** Resolves [href] found in the document at [baseUrl] to a kiwix.app URL, or null. */
  fun resolveUrl(baseUrl: String, href: String): String? {
    val base = EpubPaths.entryPathFromUrl(baseUrl) ?: return null
    val path = EpubPaths.resolve(base, href) ?: return null
    return EpubPaths.toUrl(path, href.substringAfter('#', "").ifEmpty { null })
  }

  fun nextUrl(currentUrl: String): String? =
    EpubPaths.entryPathFromUrl(currentUrl)?.let { epubPackage.nextInSpine(it) }
      ?.let { EpubPaths.toUrl(it.path) }

  fun previousUrl(currentUrl: String): String? =
    EpubPaths.entryPathFromUrl(currentUrl)?.let { epubPackage.previousInSpine(it) }
      ?.let { EpubPaths.toUrl(it.path) }

  override fun dispose() {
    disposed = true
    runCatching { zip.close() }
  }

  private fun actualEntry(path: String): String? =
    if (path in entries) path else lowerCased[path.lowercase(Locale.ROOT)]

  private companion object {
    const val MAX_FAVICON_BYTES = 256 * 1024
  }

  private fun openEntry(path: String): InputStream? {
    if (disposed) return null
    val name = actualEntry(path) ?: return null
    return try {
      zip.getEntry(name)?.let { zip.getInputStream(it) }
    } catch (_: Exception) {
      null
    }
  }
}
