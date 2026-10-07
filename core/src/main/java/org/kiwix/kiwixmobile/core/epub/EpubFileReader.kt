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

import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Serves an EPUB's contents under `https://kiwix.app/<zip entry path>`.
 * Method names mirror ZimFileReader so a shared reader interface can wrap both.
 */
@Suppress("ReturnCount")
class EpubFileReader(private val zip: ZipFile) {
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

  val title: String get() = metadata.title.ifEmpty { File(zip.name).nameWithoutExtension }

  val toc: List<TocEntry> by lazy { EpubParser.parseToc(epubPackage, opener) }

  val spineUrls: List<String> get() = epubPackage.spine.map { EpubPaths.toUrl(it.item.path) }

  val mainPageUrl: String?
    get() = epubPackage.firstLinear?.let { EpubPaths.toUrl(it.path) }

  val coverUrl: String?
    get() = metadata.coverPath?.let { actualEntry(it) }?.let { EpubPaths.toUrl(it) }

  /** Stream for an internal URL, or null for external, missing, unsafe or after [dispose]. */
  fun load(url: String): InputStream? {
    if (disposed) return null
    val path = EpubPaths.entryPathFromUrl(url) ?: return null
    return openEntry(path)
  }

  fun getMimeTypeFromUrl(url: String): String? {
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

  fun dispose() {
    disposed = true
    runCatching { zip.close() }
  }

  private fun actualEntry(path: String): String? =
    if (path in entries) path else lowerCased[path.lowercase(Locale.ROOT)]

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
