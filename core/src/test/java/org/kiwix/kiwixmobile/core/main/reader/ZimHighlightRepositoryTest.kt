/*
 * Kiwix Android
 * Copyright (c) 2026 Kiwix <android.kiwix.org>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.kiwix.kiwixmobile.core.main.reader

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kiwix.kiwixmobile.core.epub.reader.EpubHighlightColor
import org.kiwix.kiwixmobile.core.reader.ZimReaderSource
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R])
class ZimHighlightRepositoryTest {
  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun `saves highlights separately and removes only requested ids`() = runBlocking {
    val filesDirectory = temporaryFolder.newFolder("private-files")
    val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
      override fun getFilesDir(): File = filesDirectory
    }
    val repository = ZimHighlightRepository(context, Dispatchers.Unconfined)
    val zimFile = temporaryFolder.newFile("book.zim")
    val source = ZimReaderSource(zimFile)
    val first = ZimHighlight(
      id = "first",
      pageUrl = "zim://book/article",
      text = "first quote",
      prefix = "before ",
      suffix = " after",
      color = EpubHighlightColor.LIGHT_YELLOW
    )
    val second = first.copy(id = "second", text = "second quote")

    repository.add("archive-uuid", "Book title", first, source)
    repository.add("archive-uuid", "Book title", second, source)
    repository.remove("archive-uuid", "Book title", setOf("first"), source)

    val sidecar = repository.annotationFile(source)!!
    assertTrue(sidecar.isFile)
    assertTrue(sidecar.name.endsWith(".annotation"))
    assertTrue(sidecar.parentFile == zimFile.parentFile)
    assertEquals(listOf(second), repository.load("archive-uuid", source))
    assertEquals(emptyList<ZimHighlight>(), repository.load("another-archive", null))
  }
}
