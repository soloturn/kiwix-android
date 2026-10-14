/*
 * Kiwix Android
 * Copyright (c) 2026 Kiwix <android.kiwix.org>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.kiwix.kiwixmobile.core.epub.reader

import org.readium.r2.shared.publication.Locator

internal fun Locator.sameBookmarkPosition(other: Locator): Boolean {
  if (this == other) return true
  val matchingPositions = locations.position?.let { position ->
    other.locations.position?.let { otherPosition -> position == otherPosition }
  }
  val matchingProgression = locations.progression?.let { progression ->
    other.locations.progression?.let { otherProgression ->
      kotlin.math.abs(progression - otherProgression) <= BOOKMARK_PROGRESSION_TOLERANCE
    }
  } ?: false
  val matchingTotalProgression = locations.totalProgression?.let { progression ->
    other.locations.totalProgression?.let { otherProgression ->
      kotlin.math.abs(progression - otherProgression) <= BOOKMARK_PROGRESSION_TOLERANCE
    }
  } ?: false
  return href == other.href && (matchingPositions ?: (matchingProgression || matchingTotalProgression))
}

private const val BOOKMARK_PROGRESSION_TOLERANCE = 0.002
