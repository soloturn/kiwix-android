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

package org.kiwix.kiwixmobile.core.epub.reader

import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.TransformingContainer
import org.readium.r2.shared.util.resource.TransformingResource
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** CSS that keeps wide content inside the paginated column instead of being clipped. */
object EpubContentCss {
  const val CSS = "img,svg,video,canvas{max-width:100%!important;height:auto!important;" +
    "max-height:90vh!important;object-fit:contain}" +
    "pre{white-space:pre-wrap!important;overflow-wrap:anywhere!important}" +
    "table{max-width:100%!important;table-layout:auto!important}" +
    "td,th{overflow-wrap:anywhere;word-break:break-word}"

  private val HEAD_END = Regex("</head\\s*>", RegexOption.IGNORE_CASE)
  private val HEAD_START = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
  private val HTML_EXTENSIONS = setOf("xhtml", "html", "htm")
  private const val STYLE_OPEN = "<style id=\"kiwix-epub-fit\">"
  private const val PROBE_BYTES = 1024
  private val UTF8_NAMES = setOf("utf-8", "utf8")
  private val DECLARED_ENCODING = listOf(
    Regex("<\\?xml[^>]*?encoding\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
    Regex("<meta[^>]*?charset\\s*=\\s*[\"']?([\\w.:-]+)", RegexOption.IGNORE_CASE)
  )

  fun isHtml(url: Url): Boolean =
    url.path?.substringAfterLast('.', "")?.lowercase() in HTML_EXTENSIONS

  /**
   * [bytes] with the rules added; returned untouched unless they are strictly valid UTF-8 with
   * no other declared encoding, as re-encoding as UTF-8 would corrupt them.
   */
  fun transform(bytes: ByteArray): ByteArray {
    val html = decodeUtf8(bytes) ?: return bytes
    return inject(html).toByteArray(Charsets.UTF_8)
  }

  private fun decodeUtf8(bytes: ByteArray): String? {
    val utf16 = bytes.size >= 2 &&
      (
        bytes[0] == 0.toByte() ||
          bytes[1] == 0.toByte() ||
          (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) ||
          (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
      )
    val probe = String(bytes, 0, minOf(bytes.size, PROBE_BYTES), Charsets.ISO_8859_1)
    val declared = DECLARED_ENCODING.firstNotNullOfOrNull { it.find(probe)?.groupValues?.get(1) }
    if (utf16 || (declared != null && declared.lowercase() !in UTF8_NAMES)) return null
    return try {
      Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    } catch (_: CharacterCodingException) {
      null
    }
  }

  /** Adds the rules as the last style of `<head>`; returns [html] unchanged if it has none. */
  fun inject(html: String): String {
    val style = "$STYLE_OPEN$CSS</style>"
    val end = HEAD_END.find(html)
    val start = HEAD_START.find(html)
    return when {
      end != null -> html.replaceRange(end.range, style + end.value)
      start != null -> html.replaceRange(start.range, start.value + style)
      else -> html
    }
  }
}

/**
 * Wraps the container so every HTML resource carries [EpubContentCss.CSS]. Fixed-layout books
 * position everything exactly, so they are left alone.
 */
fun Publication.Builder.injectContentCss() {
  if (manifest.metadata.layout == Layout.FIXED) return
  container = TransformingContainer(container) { url: Url, resource: Resource ->
    if (EpubContentCss.isHtml(url)) {
      TransformingResource(resource) { bytes -> Try.success(EpubContentCss.transform(bytes)) }
    } else {
      resource
    }
  }
}
