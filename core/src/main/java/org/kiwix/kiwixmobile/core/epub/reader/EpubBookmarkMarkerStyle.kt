/*
 * Kiwix Android
 * Copyright (c) 2026 Kiwix <android.kiwix.org>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.kiwix.kiwixmobile.core.epub.reader

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.html.HtmlDecorationTemplate
import org.readium.r2.navigator.html.HtmlDecorationTemplates
import org.readium.r2.shared.publication.Locator

@Parcelize
internal data class EpubBookmarkMarkerStyle(val bookmarked: Boolean) : Decoration.Style, Parcelable

internal fun epubBookmarkDecorationTemplates(): HtmlDecorationTemplates =
  HtmlDecorationTemplates.defaultTemplates().apply {
    this[EpubBookmarkMarkerStyle::class] = HtmlDecorationTemplate(
      layout = HtmlDecorationTemplate.Layout.BOUNDS,
      width = HtmlDecorationTemplate.Width.PAGE,
      element = { decoration ->
        val bookmarked = (decoration.style as EpubBookmarkMarkerStyle).bookmarked
        val path = if (bookmarked) {
          "M6 3a2 2 0 0 0-2 2v16l8-4 8 4V5a2 2 0 0 0-2-2H6z"
        } else {
          "M6 3h12a2 2 0 0 1 2 2v16l-8-4-8 4V5a2 2 0 0 1 2-2zm0 2v12.76l6-3 6 3V5H6z"
        }
        val label = if (bookmarked) "Remove bookmark" else "Add bookmark"
        """
          <button class="kiwix-epub-bookmark" type="button" data-activable="1" aria-label="$label">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="$path"/></svg>
          </button>
        """.trimIndent()
      },
      stylesheet = """
        .kiwix-epub-bookmark {
          position: fixed;
          z-index: 2147483647;
          inset-block-start: 50%;
          inset-inline-end: 2px;
          transform: translateY(-50%);
          display: grid;
          place-items: center;
          width: 44px;
          height: 48px;
          padding: 8px;
          border: 0;
          border-radius: 8px;
          background: rgba(255, 255, 255, 0.88);
          color: #263238;
          box-shadow: 0 1px 4px rgba(0, 0, 0, 0.28);
          cursor: pointer;
          pointer-events: auto;
        }
        .kiwix-epub-bookmark svg { width: 26px; height: 26px; }
        .kiwix-epub-bookmark path {
          fill: none;
          stroke: currentColor;
          stroke-width: 1.8;
          stroke-linejoin: round;
        }
        .kiwix-epub-bookmark[aria-label="Remove bookmark"] path {
          fill: currentColor;
        }
      """.trimIndent()
    )
  }

internal fun bookmarkMarker(locator: Locator, bookmarked: Boolean) =
  Decoration(
    id = "kiwix-bookmark-toggle",
    locator = locator,
    style = EpubBookmarkMarkerStyle(bookmarked)
  )

internal fun Locator.sameBookmarkPosition(other: Locator): Boolean {
  val matchingPositions = locations.position?.let { position ->
    other.locations.position?.let { otherPosition -> position == otherPosition }
  }
  val matchingProgression = locations.progression?.let { progression ->
    other.locations.progression?.let { otherProgression ->
      kotlin.math.abs(progression - otherProgression) <= BOOKMARK_PROGRESSION_TOLERANCE
    }
  } ?: false
  return href == other.href && (matchingPositions ?: matchingProgression)
}

private const val BOOKMARK_PROGRESSION_TOLERANCE = 0.002
