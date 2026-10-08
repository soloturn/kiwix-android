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

import org.readium.r2.shared.publication.Locator
import kotlin.math.roundToInt

private const val PERCENT = 100

// Keeps i / n * n from flooring to i - 1 in float arithmetic.
private const val FLOAT_SLACK = 0.001f

/**
 * Where the reader is in the whole book. [position] is a 1-based index into Readium's
 * `positions()` (a stable unit of about 1024 characters, not a screen page); 0 when they are
 * not loaded.
 */
data class EpubProgress(val fraction: Float, val position: Int, val total: Int) {
  val percent get() = (fraction * PERCENT).roundToInt()
}

/** The book-wide fraction (0..1) of [locator], from Readium's total progression. */
private fun Locator?.bookFraction(total: Int): Float {
  val locations = this?.locations
  val byPosition = locations?.position?.takeIf { total > 0 }?.let { (it - 1f) / total }
  return (locations?.totalProgression?.toFloat() ?: byPosition ?: 0f).coerceIn(0f, 1f)
}

/** 1-based position for [fraction]; the last one for 1.0. */
fun positionForFraction(fraction: Float, total: Int): Int =
  if (total <= 0) 0 else (fraction * total + FLOAT_SLACK).toInt().coerceIn(0, total - 1) + 1

fun readingProgress(locator: Locator?, positionCount: Int): EpubProgress {
  val fraction = locator.bookFraction(positionCount)
  val reported = locator?.locations?.position?.takeIf { positionCount > 0 }
    ?.coerceIn(1, positionCount)
  return EpubProgress(
    fraction,
    reported ?: positionForFraction(fraction, positionCount),
    positionCount
  )
}

/** The Readium position a scrubber [fraction] points at, or null with no positions. */
fun seekTarget(positions: List<Locator>, fraction: Float): Locator? =
  positions.getOrNull(positionForFraction(fraction, positions.size) - 1)

/** Title of the table-of-contents entry for [locator]'s file, preferring the whole-file entry. */
fun chapterTitle(toc: List<EpubTocItem>, locator: Locator?): String? {
  locator ?: return null
  val href = locator.href.removeFragment()
  val matches = toc.filter { it.link.url().removeFragment() == href }
  val entry = matches.firstOrNull { it.link.url() == href } ?: matches.firstOrNull()
  return entry?.title?.takeIf { it.isNotBlank() } ?: locator.title?.takeIf { it.isNotBlank() }
}
