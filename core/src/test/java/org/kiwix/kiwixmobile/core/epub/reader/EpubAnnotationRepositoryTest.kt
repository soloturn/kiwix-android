/*
 * Kiwix Android
 * Copyright (c) 2026 Kiwix <android.kiwix.org>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.kiwix.kiwixmobile.core.epub.reader

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R])
class EpubAnnotationRepositoryTest {
  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun `persists annotations to a separate Readium sidecar without changing its set id`() = runBlocking {
    val filesDirectory = temporaryFolder.newFolder("private-files")
    val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
      override fun getFilesDir() = filesDirectory
    }
    val repository = EpubAnnotationRepository(context, Dispatchers.Unconfined)
    val locator = Locator(
      href = Url("chapter.xhtml")!!,
      mediaType = MediaType.XHTML,
      locations = Locator.Locations(progression = 0.5)
    )
    val bookmark = EpubAnnotation("bookmark", EpubAnnotationKind.BOOKMARK, locator, "")
    val note = EpubAnnotation("note", EpubAnnotationKind.NOTE, locator, "quote", "note text")

    repository.save("book-id", "Title", listOf(bookmark))
    val file = repository.annotationFile("book-id")
    val initialSetId = JSONObject(file.readText()).getString("id")
    repository.save("book-id", "Title", listOf(bookmark, note))

    assertTrue(file.isFile)
    assertTrue(file.name.endsWith(".annotation"))
    assertEquals(initialSetId, JSONObject(file.readText()).getString("id"))
    assertEquals(listOf("bookmark", "note"), repository.load("book-id").map { it.id })
  }
}
