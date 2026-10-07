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

import java.util.Locale

object EpubMimeTypes {
  const val XHTML = "application/xhtml+xml"

  private val byExtension = mapOf(
    "xhtml" to XHTML,
    "xht" to XHTML,
    "html" to "text/html",
    "htm" to "text/html",
    "css" to "text/css",
    "js" to "text/javascript",
    "xml" to "application/xml",
    "ncx" to "application/x-dtbncx+xml",
    "opf" to "application/oebps-package+xml",
    "svg" to "image/svg+xml",
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "otf" to "font/otf",
    "ttf" to "font/ttf",
    "woff" to "font/woff",
    "woff2" to "font/woff2",
    "mp3" to "audio/mpeg",
    "mp4" to "video/mp4",
    "m4a" to "audio/mp4",
    "ogg" to "audio/ogg",
    "oga" to "audio/ogg",
    "wav" to "audio/wav",
    "webm" to "video/webm",
    "smil" to "application/smil+xml",
    "pls" to "application/pls+xml",
    "txt" to "text/plain"
  )

  fun fromPath(path: String): String? {
    val name = path.substringAfterLast('/')
    if (!name.contains('.')) return null
    return byExtension[name.substringAfterLast('.').lowercase(Locale.ROOT)]
  }
}
