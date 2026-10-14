/*
 * Kiwix Android
 * Copyright (c) 2026 Kiwix <android.kiwix.org>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.kiwix.kiwixmobile.core.epub.reader

import android.os.Build
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R])
class EpubAnnotationSetCodecTest {
  private val selection = Locator(
    href = requireNotNull(Url("Text/chapter.xhtml")),
    mediaType = MediaType.XHTML,
    locations = Locator.Locations(progression = 0.25),
    text = Locator.Text("Before text ", "selected words", " after text")
  )

  @Test
  fun `encodes and decodes bookmark note and highlight as a Readium AnnotationSet`() {
    val annotations = listOf(
      EpubAnnotation("bookmark-id", EpubAnnotationKind.BOOKMARK, selection, "", title = "Chapter 1"),
      EpubAnnotation("note-id", EpubAnnotationKind.NOTE, selection, "selected words", "My note"),
      EpubAnnotation(
        "highlight-id",
        EpubAnnotationKind.HIGHLIGHT,
        selection,
        "selected words",
        title = "Chapter 1",
        color = EpubHighlightColor.LIGHT_BLUE
      ),
      EpubAnnotation("favorite-id", EpubAnnotationKind.FAVORITE, selection, "selected words")
    )

    val encoded = EpubAnnotationSetCodec.encode("set-id", "Book title", annotations)
    val set = JSONObject(encoded)
    val items = set.getJSONArray("items")

    assertEquals(
      "http://www.w3.org/ns/anno.jsonld",
      set.getJSONArray("@context").getString(0)
    )
    assertEquals("AnnotationSet", set.getString("type"))
    assertEquals("Book title", set.getJSONObject("about").getString("dc:title"))
    assertEquals(4, items.length())
    assertEquals("bookmarking", items.getJSONObject(0).getString("motivation"))
    assertFalse(items.getJSONObject(0).has("body"))
    assertEquals("commenting", items.getJSONObject(1).getString("motivation"))
    assertEquals("My note", items.getJSONObject(1).getJSONObject("body").getString("value"))
    assertEquals("highlighting", items.getJSONObject(2).getString("motivation"))
    assertFalse(items.getJSONObject(2).has("body"))
    assertEquals("LIGHT_BLUE", items.getJSONObject(2).getString("kiwix:color"))
    assertEquals("Chapter 1", items.getJSONObject(2).getString("kiwix:title"))
    assertEquals("favorite", items.getJSONObject(3).getString("kiwix:kind"))
    assertEquals(
      "TextQuoteSelector",
      items.getJSONObject(2).getJSONObject("target").getJSONArray("selector")
        .getJSONObject(1).getString("type")
    )

    val restored = EpubAnnotationSetCodec.decode(encoded)
    assertEquals(annotations.map { it.id to it.kind }, restored.map { it.id to it.kind })
    assertEquals("Text/chapter.xhtml", restored[2].locator.href.toString())
    assertEquals(0.25, restored[2].locator.locations.progression!!, 0.0)
    assertEquals("selected words", restored[2].text)
    assertEquals("My note", restored[1].note)
    assertEquals(EpubHighlightColor.LIGHT_BLUE, restored[2].color)
    assertEquals("Chapter 1", restored[2].title)
    assertEquals(EpubAnnotationKind.FAVORITE, restored[3].kind)
  }

  @Test
  fun `ignores malformed or unsupported annotations`() {
    val malformed = """{"items":[{}, {"motivation":"reply"}]}"""
    assertTrue(EpubAnnotationSetCodec.decode(malformed).isEmpty())
  }
}
