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

class EpubException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class EpubMetadata(
  val title: String,
  val creators: List<String>,
  val language: String?,
  val identifier: String?,
  /** Zip entry path of the cover image, or null if none is declared. */
  val coverPath: String?,
  val publisher: String = "",
  val date: String = "",
  val description: String = ""
)

/** [path] is the normalized zip entry path (already resolved against the OPF location). */
data class ManifestItem(
  val id: String,
  val path: String,
  val mediaType: String?,
  val properties: Set<String>
)

data class SpineItem(val item: ManifestItem, val linear: Boolean)

/** [path] is null when the target can't be resolved inside the zip. */
data class TocEntry(
  val title: String,
  val path: String?,
  val fragment: String?,
  val children: List<TocEntry>
) {
  val url: String? get() = path?.let { EpubPaths.toUrl(it, fragment) }
}

data class EpubPackage(
  val opfPath: String,
  val version: String,
  val metadata: EpubMetadata,
  val manifest: Map<String, ManifestItem>,
  val spine: List<SpineItem>,
  val navPath: String?,
  val ncxPath: String?
) {
  private val manifestByPath: Map<String, ManifestItem> = manifest.values.associateBy { it.path }

  fun itemForPath(path: String): ManifestItem? = manifestByPath[path]

  fun spineIndexOf(path: String): Int = spine.indexOfFirst { it.item.path == path }

  /** Next spine document after [path], skipping non-linear items; null at the end or if unknown. */
  fun nextInSpine(path: String): ManifestItem? {
    val i = spineIndexOf(path)
    if (i < 0) return null
    return spine.drop(i + 1).firstOrNull { it.linear }?.item
  }

  fun previousInSpine(path: String): ManifestItem? {
    val i = spineIndexOf(path)
    if (i < 0) return null
    return spine.take(i).lastOrNull { it.linear }?.item
  }

  val firstLinear: ManifestItem? get() = spine.firstOrNull { it.linear }?.item ?: spine.firstOrNull()?.item
}
