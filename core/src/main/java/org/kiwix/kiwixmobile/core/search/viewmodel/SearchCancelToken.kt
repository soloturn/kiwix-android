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

package org.kiwix.kiwixmobile.core.search.viewmodel

/**
 * App-owned cancellation flag for one in-flight search.
 *
 * Today libzim exposes no way to abort a Xapian match mid-flight, so this is only
 * polled between results. When libzim grows one, the seam is a `PostingSource`
 * subclass that reads this flag and throws, combined with the real query via
 * `OP_FILTER` — that stops sooner than an `Enquire`-level cancel (for `OP_PHRASE`
 * subqueries the and-like ops combine and the positional check is hoisted above
 * them). Nothing in the match path may catch that exception; it is the signal.
 *
 * libzim already uses that exact shape for geo filtering — `src/search.cpp` builds
 * `Query(OP_FILTER, xquery, geoQuery)` around a `LatLongDistancePostingSource`.
 *
 * JNI maps `std::exception` to `java.lang.Exception`, not `CancellationException`,
 * so the match path rethrows a `CancellationException` once this flag is set.
 */
class SearchCancelToken {
  @Volatile
  var isCancelled: Boolean = false
    private set

  fun cancel() {
    isCancelled = true
  }
}
