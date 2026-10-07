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

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

class EpubContentCssTest {
  @Test
  fun `css is injected as the last head child`() {
    val out = EpubContentCss.inject("<html><head><title>t</title></HEAD><body/></html>")

    assertTrue(out.contains("<title>t</title><style id=\"kiwix-epub-fit\">"))
    assertTrue(out.contains("${EpubContentCss.CSS}</style></HEAD>"))
  }

  @Test
  fun `css goes after the head start when there is no head end`() {
    val out = EpubContentCss.inject("<html><head lang=\"en\"><body/></html>")

    assertTrue(out.startsWith("<html><head lang=\"en\"><style"))
  }

  @Test
  fun `html without a head is unchanged`() {
    assertEquals("<p>x</p>", EpubContentCss.inject("<p>x</p>"))
  }

  private fun transform(bytes: ByteArray, withCss: Boolean = true) =
    String(EpubContentCss.transform(bytes, withCss), Charsets.UTF_8)

  @Test
  fun `utf-8 content, with or without a declaration or BOM, gets the rules and keeps its text`() {
    val html = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html><head></head><body>caf\u00e9 \u4e2d</body></html>"
    assertTrue(transform(html.toByteArray()).contains(EpubContentCss.CSS))
    assertTrue(transform(html.toByteArray()).contains("caf\u00e9 \u4e2d"))
    val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + html.toByteArray()
    assertTrue(transform(bom).contains(EpubContentCss.CSS))
  }

  private fun withPolicy(html: String, charset: Charset) =
    html.replaceFirst("<head>", "<head>${EpubContentCss.CONNECT_POLICY}").toByteArray(charset)

  @Test
  fun `content in another encoding gets only the policy, other bytes unchanged`() {
    val latin1 = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><html><head></head><body>caf\u00e9</body></html>"
    assertArrayEquals(
      withPolicy(latin1, Charsets.ISO_8859_1),
      EpubContentCss.transform(latin1.toByteArray(Charsets.ISO_8859_1))
    )

    val meta = "<html><head><meta charset=\"windows-1252\"></head><body>caf\u00e9</body></html>"
    assertArrayEquals(
      withPolicy(meta, Charsets.ISO_8859_1),
      EpubContentCss.transform(meta.toByteArray(Charsets.ISO_8859_1))
    )
  }

  @Test
  fun `utf-16 passes through untouched, invalid utf-8 gets only the policy`() {
    val utf16 = "<html><head></head><body>x</body></html>".toByteArray(Charsets.UTF_16)
    assertArrayEquals(utf16, EpubContentCss.transform(utf16))
    val utf16le = "<html><head></head></html>".toByteArray(Charsets.UTF_16LE)
    assertArrayEquals(utf16le, EpubContentCss.transform(utf16le))

    val invalid = "<html><head></head><body>".toByteArray() + byteArrayOf(0xE9.toByte()) + "</body></html>".toByteArray()
    val expected = "<html><head>${EpubContentCss.CONNECT_POLICY}</head><body>".toByteArray() +
      byteArrayOf(0xE9.toByte()) + "</body></html>".toByteArray()
    assertArrayEquals(expected, EpubContentCss.transform(invalid))
  }

  @Test
  fun `the policy is the first head child and forbids connections`() {
    val out = EpubContentCss.injectPolicy("<html><head lang=\"en\"><script>x()</script></head></html>")

    assertTrue(out.startsWith("<html><head lang=\"en\"><meta http-equiv=\"Content-Security-Policy\""))
    assertTrue(out.indexOf("connect-src 'none'") < out.indexOf("<script>"))
    assertEquals("<p>x</p>", EpubContentCss.injectPolicy("<p>x</p>"))
  }

  @Test
  fun `without the rules only the policy is added`() {
    val out = transform("<html><head></head></html>".toByteArray(), withCss = false)

    assertTrue(out.contains(EpubContentCss.CONNECT_POLICY))
    assertFalse(out.contains("<style"))
  }

  @Test
  fun `css covers media, pre and tables`() {
    listOf("img", "pre{", "table{", "overflow-wrap:anywhere").forEach {
      assertTrue(EpubContentCss.CSS.contains(it), it)
    }
  }
}
