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

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.InputStream
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Opens a zip entry by normalized path; returns null if absent. Caller closes the stream. */
typealias EntryOpener = (String) -> InputStream?

@Suppress("ReturnCount", "ThrowsCount")
object EpubParser {
  private const val CONTAINER_PATH = "META-INF/container.xml"
  private const val OPS_NS = "http://www.idpf.org/2007/ops"

  fun parsePackage(open: EntryOpener): EpubPackage {
    val container = parseXml(open, CONTAINER_PATH)
      ?: throw EpubException("Missing $CONTAINER_PATH")
    val rootfile = container.descendants("rootfile").firstOrNull { it.hasAttribute("full-path") }
      ?: throw EpubException("container.xml has no rootfile")
    val opfPath = EpubPaths.normalize(rootfile.getAttribute("full-path"))
      ?: throw EpubException("Invalid OPF path")
    val opf = parseXml(open, opfPath) ?: throw EpubException("Missing package document $opfPath")
    val pkg = opf.documentElement
    val version = pkg.getAttribute("version").ifEmpty { "2.0" }

    val manifest = LinkedHashMap<String, ManifestItem>()
    pkg.descendants("manifest").firstOrNull()?.children("item")?.forEach { el ->
      val id = el.getAttribute("id")
      val path = EpubPaths.resolve(opfPath, el.getAttribute("href"))
      if (id.isNotEmpty() && path != null) {
        manifest[id] = ManifestItem(
          id,
          path,
          el.getAttribute("media-type").ifEmpty { null },
          el.getAttribute("properties").split(' ', '\t', '\n').filter { it.isNotEmpty() }.toSet()
        )
      }
    }

    val spineEl = pkg.descendants("spine").firstOrNull()
    val spine = spineEl?.children("itemref")?.mapNotNull { ref ->
      manifest[ref.getAttribute("idref")]?.let { SpineItem(it, ref.getAttribute("linear") != "no") }
    }.orEmpty()

    val ncx = spineEl?.getAttribute("toc")?.let { manifest[it] }
      ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
    val nav = manifest.values.firstOrNull { "nav" in it.properties }

    return EpubPackage(
      opfPath,
      version,
      parseMetadata(pkg, manifest),
      manifest,
      spine,
      nav?.path,
      ncx?.path
    )
  }

  private fun parseMetadata(pkg: Element, manifest: Map<String, ManifestItem>): EpubMetadata {
    val md = pkg.descendants("metadata").firstOrNull()
    fun texts(name: String) = md?.children(name)?.map { it.textContent.trim() }
      ?.filter { it.isNotEmpty() }.orEmpty()

    val uniqueId = pkg.getAttribute("unique-identifier")
    val identifier = md?.children("identifier")
      ?.firstOrNull { uniqueId.isNotEmpty() && it.getAttribute("id") == uniqueId }
      ?.textContent?.trim()?.ifEmpty { null }
      ?: texts("identifier").firstOrNull()

    val cover = manifest.values.firstOrNull { "cover-image" in it.properties }
      ?: md?.children("meta")
        ?.firstOrNull { it.getAttribute("name") == "cover" }
        ?.let { manifest[it.getAttribute("content")] }

    return EpubMetadata(
      title = texts("title").firstOrNull().orEmpty(),
      creators = texts("creator"),
      language = texts("language").firstOrNull(),
      identifier = identifier,
      coverPath = cover?.path
    )
  }

  /** EPUB3 nav document first, then EPUB2 NCX; empty list if neither yields entries. */
  fun parseToc(pkg: EpubPackage, open: EntryOpener): List<TocEntry> {
    pkg.navPath?.let { path ->
      val toc = runCatching { parseNav(path, open) }.getOrNull()
      if (!toc.isNullOrEmpty()) return toc
    }
    pkg.ncxPath?.let { path ->
      return runCatching { parseNcx(path, open) }.getOrNull().orEmpty()
    }
    return emptyList()
  }

  private fun parseNav(navPath: String, open: EntryOpener): List<TocEntry> {
    val doc = parseXml(open, navPath) ?: return emptyList()
    val navs = doc.descendants("nav")
    val tocNav = navs.firstOrNull { epubType(it).split(' ').contains("toc") } ?: navs.firstOrNull()
      ?: return emptyList()
    val ol = tocNav.children("ol").firstOrNull() ?: return emptyList()
    return navList(ol, navPath)
  }

  private fun navList(ol: Element, base: String): List<TocEntry> =
    ol.children("li").mapNotNull { li ->
      val label = li.children("a").firstOrNull() ?: li.children("span").firstOrNull()
      val title = label?.textContent?.normalizeSpace().orEmpty()
      val children = li.children("ol").firstOrNull()?.let { navList(it, base) }.orEmpty()
      if (title.isEmpty() && children.isEmpty()) {
        null
      } else {
        val href = label?.takeIf { it.localOrTagName() == "a" }?.getAttribute("href").orEmpty()
        tocEntry(title, base, href, children)
      }
    }

  private fun parseNcx(ncxPath: String, open: EntryOpener): List<TocEntry> {
    val doc = parseXml(open, ncxPath) ?: return emptyList()
    val map = doc.descendants("navMap").firstOrNull() ?: return emptyList()
    return ncxPoints(map, ncxPath)
  }

  private fun ncxPoints(parent: Element, base: String): List<TocEntry> =
    parent.children("navPoint").map { point ->
      val title = point.children("navLabel").firstOrNull()?.textContent?.normalizeSpace().orEmpty()
      val src = point.children("content").firstOrNull()?.getAttribute("src").orEmpty()
      tocEntry(title, base, src, ncxPoints(point, base))
    }

  private fun tocEntry(title: String, base: String, href: String, children: List<TocEntry>) =
    TocEntry(
      title,
      EpubPaths.resolve(base, href),
      href.substringAfter('#', "").ifEmpty { null }?.let { EpubPaths.percentDecode(it) },
      children
    )

  private fun epubType(el: Element): String =
    el.getAttributeNS(OPS_NS, "type").ifEmpty { el.getAttribute("epub:type") }

  @Suppress("TooGenericExceptionCaught")
  private fun parseXml(open: EntryOpener, path: String): Document? {
    val bytes = open(path)?.use { it.readBytes() } ?: return null
    return try {
      secureFactory().newDocumentBuilder().apply {
        // Never fetch DTDs or external entities, even if the feature flags are unsupported.
        setEntityResolver { _, _ -> InputSource(StringReader("")) }
      }.parse(java.io.ByteArrayInputStream(bytes))
    } catch (e: Exception) {
      throw EpubException("Malformed XML in $path", e)
    }
  }

  private fun secureFactory(): DocumentBuilderFactory =
    DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = true
      isXIncludeAware = false
      isExpandEntityReferences = false
      isValidating = false
      listOf(
        XMLConstants.FEATURE_SECURE_PROCESSING to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false
      ).forEach { (feature, value) ->
        // Not every JVM/Android parser knows every feature; the EntityResolver is the backstop.
        try {
          setFeature(feature, value)
        } catch (_: Exception) {
        }
      }
    }

  private fun Node.localOrTagName(): String = localName ?: nodeName.substringAfter(':')

  private fun Element.children(name: String): List<Element> {
    val out = ArrayList<Element>()
    var n = firstChild
    while (n != null) {
      if (n is Element && n.localOrTagName() == name) out.add(n)
      n = n.nextSibling
    }
    return out
  }

  private fun Node.descendants(name: String): List<Element> {
    val out = ArrayList<Element>()
    fun walk(node: Node) {
      var c = node.firstChild
      while (c != null) {
        if (c is Element) {
          if (c.localOrTagName() == name) out.add(c)
          walk(c)
        }
        c = c.nextSibling
      }
    }
    walk(this)
    return out
  }

  private fun String.normalizeSpace() = trim().replace(Regex("\\s+"), " ")
}
