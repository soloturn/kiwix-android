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

  /** Readium serves every page request in-process; this stops WebSockets, which bypass that. */
  const val CONNECT_POLICY =
    "<meta http-equiv=\"Content-Security-Policy\" content=\"connect-src 'none'\"/>"
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
   * [bytes] with the policy and, if [withCss], the rules added. The rules are only added to
   * strictly valid UTF-8 with no other declared encoding, as re-encoding would corrupt the rest;
   * the policy is ASCII, so it is added to any ASCII-compatible encoding. UTF-16 is untouched.
   */
  fun transform(bytes: ByteArray, withCss: Boolean = true): ByteArray {
    val styled = if (withCss) {
      decodeUtf8(bytes)?.let { inject(it).toByteArray(Charsets.UTF_8) } ?: bytes
    } else {
      bytes
    }
    if (isUtf16(styled)) return styled
    return injectPolicy(String(styled, Charsets.ISO_8859_1)).toByteArray(Charsets.ISO_8859_1)
  }

  /**
   * Adds [CONNECT_POLICY] as the first child of `<head>`, before any script; returns [html]
   * unchanged if it has no head.
   */
  fun injectPolicy(html: String): String {
    val start = HEAD_START.find(html) ?: return html
    return html.replaceRange(start.range, start.value + CONNECT_POLICY)
  }

  private fun isUtf16(bytes: ByteArray) = bytes.size >= 2 &&
    (
      bytes[0] == 0.toByte() ||
        bytes[1] == 0.toByte() ||
        (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) ||
        (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
    )

  private fun decodeUtf8(bytes: ByteArray): String? {
    val utf16 = isUtf16(bytes)
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
 * Wraps the container so every HTML resource carries the connection policy and, except in
 * fixed-layout books, which position everything exactly, [EpubContentCss.CSS].
 */
fun Publication.Builder.injectContentCss() {
  val withCss = manifest.metadata.layout != Layout.FIXED
  container = TransformingContainer(container) { url: Url, resource: Resource ->
    if (EpubContentCss.isHtml(url)) {
      TransformingResource(resource) { bytes ->
        Try.success(EpubContentCss.transform(bytes, withCss))
      }
    } else {
      resource
    }
  }
}
