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

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.kiwix.kiwixmobile.core.di.IoDispatcher
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.epub.EpubParser
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

class EpubOpenException(message: String) : Exception(message)

/** The single EPUB parser of the app: opens a [File] as a Readium [Publication]. */
@Singleton
class EpubPublicationOpener @Inject constructor(
  @param:ApplicationContext private val context: Context,
  @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
  private val assetRetriever by lazy { AssetRetriever(context.contentResolver, DefaultHttpClient()) }
  private val opener by lazy { PublicationOpener(publicationParser = EpubParser()) }

  /** Every reading position of [publication]; empty if they can't be computed. */
  suspend fun positions(publication: Publication): List<Locator> = withContext(ioDispatcher) {
    runCatching { publication.positions() }.getOrDefault(emptyList())
  }

  /** The caller owns the result and must [Publication.close] it. */
  suspend fun open(file: File): Result<Publication> = withContext(ioDispatcher) {
    runCatching {
      val asset = assetRetriever.retrieve(file).getOrElse { throw EpubOpenException("$it") }
      val publication = opener
        .open(asset, allowUserInteraction = false, onCreatePublication = { injectContentCss() })
        .getOrElse { throw EpubOpenException("$it") }
      if (!publication.conformsTo(Publication.Profile.EPUB)) {
        publication.close()
        throw EpubOpenException("Not an EPUB: ${file.name}")
      }
      publication
    }
  }
}
