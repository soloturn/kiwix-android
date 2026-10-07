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

import java.io.ByteArrayOutputStream
import java.net.URI

/** Pure path/URL helpers for addressing entries inside an EPUB zip. */
@Suppress("MagicNumber", "ReturnCount")
object EpubPaths {
  const val CONTENT_PREFIX = "https://kiwix.app/"
  private const val CONTENT_HOST = "kiwix.app"

  /**
   * Resolves [href] against the zip entry [baseEntry] (may be empty) and returns a normalized,
   * percent-decoded entry path without fragment/query, or null if the href is external,
   * malformed, or escapes the zip root.
   */
  fun resolve(baseEntry: String, href: String): String? {
    val noFragment = href.substringBefore('#').substringBefore('?')
    if (noFragment.isEmpty() || hasScheme(noFragment) || noFragment.startsWith("//")) return null
    val decoded = percentDecode(noFragment) ?: return null
    if (decoded.any { it == '\u0000' || it == '\\' }) return null
    val combined = if (decoded.startsWith("/")) {
      decoded
    } else {
      baseEntry.substringBeforeLast('/', "").let { dir -> if (dir.isEmpty()) decoded else "$dir/$decoded" }
    }
    return normalize(combined)
  }

  /** Collapses `.`/`..`/empty segments; returns null if `..` climbs above the root. */
  fun normalize(path: String): String? {
    val out = ArrayList<String>()
    for (segment in path.split('/')) {
      when (segment) {
        "", "." -> Unit
        ".." -> if (out.isEmpty()) return null else out.removeAt(out.size - 1)
        else -> out.add(segment)
      }
    }
    return if (out.isEmpty()) null else out.joinToString("/")
  }

  /** Strict UTF-8 percent-decoding (`+` stays literal); null on malformed escapes. */
  fun percentDecode(value: String): String? {
    if (!value.contains('%')) return value
    val out = StringBuilder()
    val bytes = ByteArrayOutputStream()
    fun flush() {
      if (bytes.size() > 0) {
        out.append(String(bytes.toByteArray(), Charsets.UTF_8))
        bytes.reset()
      }
    }
    var i = 0
    while (i < value.length) {
      val c = value[i]
      if (c == '%') {
        if (i + 2 >= value.length) return null
        val hi = Character.digit(value[i + 1], 16)
        val lo = Character.digit(value[i + 2], 16)
        if (hi < 0 || lo < 0) return null
        bytes.write(hi * 16 + lo)
        i += 3
      } else {
        flush()
        out.append(c)
        i++
      }
    }
    flush()
    return out.toString()
  }

  /** Percent-encodes each path segment for use in a kiwix.app URL. */
  fun encodePath(entryPath: String): String =
    entryPath.split('/').joinToString("/") { seg ->
      val sb = StringBuilder()
      for (b in seg.toByteArray(Charsets.UTF_8)) {
        val ch = b.toInt() and 0xFF
        val safe = ch < 0x80 && (ch.toChar().isLetterOrDigit() || ch.toChar() in "-._~")
        if (safe) sb.append(ch.toChar()) else sb.append('%').append("%02X".format(ch))
      }
      sb.toString()
    }

  fun toUrl(entryPath: String, fragment: String? = null): String =
    CONTENT_PREFIX + encodePath(entryPath) + if (fragment.isNullOrEmpty()) "" else "#$fragment"

  /** True only for `https://kiwix.app/...` (no userinfo or port). Everything else is external. */
  fun isInternalUrl(url: String): Boolean = try {
    val uri = URI(url)
    uri.scheme.equals("https", ignoreCase = true) &&
      uri.host.equals(CONTENT_HOST, ignoreCase = true) &&
      uri.port == -1 &&
      uri.userInfo == null
  } catch (_: Exception) {
    false
  }

  /** Extracts the decoded, normalized entry path from an internal URL, or null. */
  fun entryPathFromUrl(url: String): String? {
    if (!isInternalUrl(url)) return null
    val raw = url.substring(url.indexOf("://") + 3).substringAfter('/', "")
    return resolve("", "/" + raw)
  }

  /** Fragment (without `#`) of [url], or null. */
  fun fragmentOf(url: String): String? = url.substringAfter('#', "").ifEmpty { null }

  private fun hasScheme(s: String): Boolean {
    val colon = s.indexOf(':')
    if (colon <= 0) return false
    val slash = s.indexOf('/')
    return (slash == -1 || colon < slash) &&
      s[0].isLetter() &&
      s.substring(0, colon).all { it.isLetterOrDigit() || it in "+-." }
  }
}
