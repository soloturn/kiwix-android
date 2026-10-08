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

package org.kiwix.kiwixmobile.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.kiwix.kiwixmobile.core.utils.files.EPUB_IMPORT_DIR
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BackupRulesTest {
  private fun excludedFileDirs(resource: String, section: String): Set<String> {
    val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
      .parse(File("src/main/res/xml/$resource")).documentElement
    val scope = if (section.isEmpty()) root else root.getElementsByTagName(section).item(0) as Element
    val excludes = scope.getElementsByTagName("exclude")
    return (0 until excludes.length).map { excludes.item(it) as Element }
      .filter { it.getAttribute("domain") == "file" }
      .map { it.getAttribute("path") }
      .toSet()
  }

  private val epubDirs = setOf("$EPUB_IMPORT_DIR/", "epub_covers/")

  @Test
  fun `legacy backup rules exclude imported epubs and covers`() {
    assertEquals(epubDirs, excludedFileDirs("backup_rules.xml", ""))
  }

  @Test
  fun `cloud backup rules exclude imported epubs and covers`() {
    assertEquals(epubDirs, excludedFileDirs("data_extraction_rules.xml", "cloud-backup"))
  }

  @Test
  fun `neither rule file excludes the datastore holding reading positions`() {
    listOf("backup_rules.xml", "data_extraction_rules.xml").forEach {
      assertTrue(excludedFileDirs(it, "").none { path -> path.startsWith("datastore") })
    }
  }
}
