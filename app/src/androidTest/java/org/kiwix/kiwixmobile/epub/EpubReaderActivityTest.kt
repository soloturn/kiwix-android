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

package org.kiwix.kiwixmobile.epub

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.kiwix.kiwixmobile.core.epub.EpubLibraryManager
import org.kiwix.kiwixmobile.core.epub.reader.EpubReaderActivity
import org.kiwix.kiwixmobile.core.epub.reader.EpubReaderUiState
import org.kiwix.kiwixmobile.core.epub.reader.EpubReaderViewModel
import org.kiwix.kiwixmobile.core.utils.TestingUtils.HILT_RULE_ORDER
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Opens a real EPUB in [EpubReaderActivity] and checks the library entry and the restored
 * reading position. Written without a device to run it on: not yet executed.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class EpubReaderActivityTest {
  @Rule(order = HILT_RULE_ORDER)
  @JvmField
  val hiltRule = HiltAndroidRule(this)

  @Inject lateinit var libraryManager: EpubLibraryManager

  private val context: Context get() = getInstrumentation().targetContext
  private lateinit var book: File
  private var scenario: ActivityScenario<EpubReaderActivity>? = null

  @Before
  fun setUp() {
    hiltRule.inject()
    book = File(File(context.filesDir, "epub").apply { mkdirs() }, "instrumented-test.epub")
    writeEpub(book)
  }

  @After
  fun tearDown() {
    scenario?.close()
    runBlocking {
      libraryManager.epubs().first().filter { it.path == book.absolutePath }
        .forEach { libraryManager.remove(it.id) }
    }
    book.delete()
  }

  @Test
  fun openingABookAddsItToTheLibraryAndRestoresTheSavedPosition() {
    val entry = runBlocking { libraryManager.add(book) }
    assertNotNull(entry)
    val id = entry!!.id
    runBlocking {
      libraryManager.saveLocator(
        id,
        """{"href":"c2.xhtml","type":"application/xhtml+xml","locations":{"progression":0.0}}"""
      )
    }

    scenario = ActivityScenario.launch<EpubReaderActivity>(EpubReaderActivity.intent(context, book))
    scenario!!.moveToState(Lifecycle.State.RESUMED)
    var viewModel: EpubReaderViewModel? = null
    scenario!!.onActivity { viewModel = ViewModelProvider(it)[EpubReaderViewModel::class.java] }
    runBlocking {
      withTimeout(OPEN_TIMEOUT_MS) {
        viewModel!!.state.first { it is EpubReaderUiState.Ready }
      }
    }

    // Overwrite the stored position, then write back what the reader restored: only a
    // restored position replaces the sentinel.
    runBlocking { libraryManager.saveLocator(id, SENTINEL) }
    scenario!!.onActivity { viewModel!!.persistPosition() }
    val saved = runBlocking {
      withTimeout(OPEN_TIMEOUT_MS) {
        var json: String?
        do {
          json = libraryManager.locator(id)
        } while (json == SENTINEL)
        json!!
      }
    }
    assertEquals("c2.xhtml", JSONObject(saved).getString("href"))
  }

  private fun writeEpub(file: File) {
    val entries = linkedMapOf(
      "mimetype" to "application/epub+zip",
      "META-INF/container.xml" to CONTAINER,
      "OEBPS/content.opf" to OPF,
      "OEBPS/nav.xhtml" to NAV,
      "OEBPS/c1.xhtml" to chapter("One"),
      "OEBPS/c2.xhtml" to chapter("Two")
    )
    ZipOutputStream(file.outputStream()).use { zip ->
      entries.forEach { (name, text) ->
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray())
        zip.closeEntry()
      }
    }
  }

  private fun chapter(title: String) =
    """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head>
<body><h1>$title</h1><p>Some text.</p></body></html>"""

  private companion object {
    const val OPEN_TIMEOUT_MS = 30_000L
    const val SENTINEL = "{}"

    const val CONTAINER = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

    const val OPF = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bid">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="bid">urn:uuid:instrumented-test</dc:identifier>
<dc:title>Instrumented Book</dc:title><dc:language>en</dc:language>
<meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
</metadata>
<manifest>
<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
<item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
<item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
</manifest>
<spine><itemref idref="c1"/><itemref idref="c2"/></spine>
</package>"""

    const val NAV = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>Contents</title></head>
<body><nav epub:type="toc"><ol>
<li><a href="c1.xhtml">One</a></li><li><a href="c2.xhtml">Two</a></li>
</ol></nav></body></html>"""
  }
}
