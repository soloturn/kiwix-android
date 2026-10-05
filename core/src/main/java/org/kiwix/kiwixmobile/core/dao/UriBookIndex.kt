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

package org.kiwix.kiwixmobile.core.dao

import org.json.JSONArray
import org.json.JSONObject
import org.kiwix.kiwixmobile.core.entity.LibkiwixBook
import org.kiwix.kiwixmobile.core.utils.files.Log
import java.io.File

/**
 * Index of books that are only reachable through a `content://` URI (SAF folder, MediaStore).
 *
 * libkiwix's library.xml cannot hold them: it has no setter for a book's path and rewrites
 * stored paths relative to the library file, which mangles URIs. The metadata is snapshotted
 * here instead, next to library.xml in internal storage so it is backed up with it.
 */
class UriBookIndex(private val file: File) {
  private val books = linkedMapOf<String, LibkiwixBook>()
  private var loaded = false

  @Synchronized
  fun all(): List<LibkiwixBook> {
    load()
    return books.values.toList()
  }

  @Synchronized
  fun contains(bookId: String): Boolean {
    load()
    return books.containsKey(bookId)
  }

  /** Adds or replaces [book]; its path must be the URI it is read from. */
  @Synchronized
  fun put(book: LibkiwixBook) {
    load()
    books[book.id] = snapshot(book)
    save()
  }

  @Synchronized
  fun remove(bookIds: Collection<String>): Boolean {
    load()
    val removed = bookIds.mapNotNull(books::remove).isNotEmpty()
    if (removed) save()
    return removed
  }

  private fun load() {
    if (loaded) return
    loaded = true
    if (!file.isFile) return
    runCatching {
      val array = JSONArray(file.readText())
      for (index in 0 until array.length()) {
        val book = fromJson(array.getJSONObject(index))
        if (book.id.isNotEmpty() && !book.path.isNullOrEmpty()) books[book.id] = book
      }
    }.onFailure { Log.e(TAG, "Could not read $file: $it") }
  }

  private fun save() {
    runCatching {
      file.parentFile?.mkdirs()
      val temp = File(file.parentFile, "${file.name}.tmp")
      temp.writeText(JSONArray(books.values.map(::toJson)).toString())
      if (!temp.renameTo(file)) {
        file.writeText(temp.readText())
        temp.delete()
      }
    }.onFailure { Log.e(TAG, "Could not write $file: $it") }
  }

  companion object {
    private const val TAG = "UriBookIndex"
    const val FILE_NAME = "uri_library.json"

    fun snapshot(book: LibkiwixBook): LibkiwixBook = fromJson(toJson(book))

    fun toJson(book: LibkiwixBook): JSONObject = JSONObject().apply {
      put("id", book.id)
      put("path", book.path.orEmpty())
      put("title", book.title)
      put("description", book.description.orEmpty())
      put("language", book.language)
      put("creator", book.creator)
      put("publisher", book.publisher)
      put("date", book.date)
      put("url", book.url.orEmpty())
      put("articleCount", book.articleCount.orEmpty())
      put("mediaCount", book.mediaCount.orEmpty())
      put("size", book.size)
      put("name", book.bookName.orEmpty())
      put("favicon", book.favicon)
      put("tags", book.tags.orEmpty())
    }

    fun fromJson(json: JSONObject): LibkiwixBook = LibkiwixBook(
      _id = json.optString("id"),
      _title = json.optString("title"),
      _description = json.optString("description"),
      _language = json.optString("language"),
      _creator = json.optString("creator"),
      _publisher = json.optString("publisher"),
      _date = json.optString("date"),
      _url = json.optString("url"),
      _articleCount = json.optString("articleCount"),
      _mediaCount = json.optString("mediaCount"),
      _size = json.optString("size"),
      _bookName = json.optString("name"),
      _favicon = json.optString("favicon"),
      _tags = json.optString("tags"),
      _path = json.optString("path")
    )
  }
}
