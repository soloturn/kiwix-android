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
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.kiwix.kiwixmobile.core.di.IoDispatcher
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class EpubAnnotationKind(val motivation: String) {
  BOOKMARK("bookmarking"),
  NOTE("commenting"),
  HIGHLIGHT("highlighting")
}

/** One annotation in the Readium AnnotationSet, with its Readium locator for navigation. */
data class EpubAnnotation(
  val id: String,
  val kind: EpubAnnotationKind,
  val locator: Locator,
  val text: String,
  val note: String? = null,
  val created: String = Instant.now().toString()
) {
  val label: String
    get() = when (kind) {
      EpubAnnotationKind.BOOKMARK -> "Bookmark"
      EpubAnnotationKind.NOTE -> note?.takeIf(String::isNotBlank) ?: text.ifBlank { "Note" }
      EpubAnnotationKind.HIGHLIGHT -> text.ifBlank { "Highlight" }
    }
}

/** Readium AnnotationSet JSON serialization and parsing for bookmark, note and highlight. */
object EpubAnnotationSetCodec {
  private const val CONTEXT = "http://www.w3.org/ns/anno.jsonld"

  fun encode(
    setId: String,
    title: String,
    annotations: List<EpubAnnotation>
  ): String = JSONObject()
    .put("@context", CONTEXT)
    .put("id", setId)
    .put("type", "AnnotationSet")
    .put(
      "generator",
      JSONObject()
        .put("id", "https://github.com/kiwix/kiwix-android")
        .put("type", "Software")
        .put("name", "Kiwix")
    )
    .put("generated", Instant.now().toString())
    .put("about", JSONObject().put("dc:format", "application/epub+zip").put("dc:title", title))
    .put("items", JSONArray().apply { annotations.forEach { put(it.toJson()) } })
    .toString(2)

  fun decode(json: String): List<EpubAnnotation> = runCatching {
    val items = JSONObject(json).getJSONArray("items")
    (0 until items.length()).mapNotNull { index ->
      runCatching { items.getJSONObject(index).toAnnotation() }.getOrNull()
    }
  }.getOrDefault(emptyList())

  private fun EpubAnnotation.toJson(): JSONObject {
    val locatorJson = locator.toJSON()
    val locations = locatorJson.optJSONObject("locations") ?: JSONObject()
    val selectors = JSONArray()
    locations.optDouble("progression").takeUnless(Double::isNaN)?.let { progression ->
      selectors.put(JSONObject().put("type", "ProgressionSelector").put("value", progression))
    }
    val textLocation = locatorJson.optJSONObject("text")
    if (text.isNotBlank() && kind != EpubAnnotationKind.BOOKMARK) {
      selectors.put(
        JSONObject()
          .put("type", "TextQuoteSelector")
          .put("prefix", textLocation?.optString("before").orEmpty())
          .put("exact", text)
          .put("suffix", textLocation?.optString("after").orEmpty())
      )
    }
    val target = JSONObject()
      .put("source", locator.href.toString())
      .put("selector", selectors)
    val annotation = JSONObject()
      .put("@context", CONTEXT)
      .put("id", "urn:uuid:$id")
      .put("type", "Annotation")
      .put("motivation", kind.motivation)
      .put("created", created)
      .put("target", target)
    if (kind == EpubAnnotationKind.NOTE) {
      annotation.put("body", JSONObject().put("type", "TextualBody").put("value", note.orEmpty()))
    }
    return annotation
  }

  private fun JSONObject.toAnnotation(): EpubAnnotation? {
    val kind = when (optString("motivation")) {
      "bookmarking" -> EpubAnnotationKind.BOOKMARK
      "commenting" -> EpubAnnotationKind.NOTE
      "highlighting", "hightlighting" -> EpubAnnotationKind.HIGHLIGHT
      else -> return null
    }
    val target = getJSONObject("target")
    val selectors = target.optJSONArray("selector") ?: JSONArray()
    val quote = (0 until selectors.length()).asSequence()
      .mapNotNull(selectors::optJSONObject)
      .firstOrNull { it.optString("type") == "TextQuoteSelector" }
    val progression = (0 until selectors.length()).asSequence()
      .mapNotNull(selectors::optJSONObject)
      .firstOrNull { it.optString("type") == "ProgressionSelector" }
      ?.optDouble("value")
      ?.takeUnless(Double::isNaN)
    val exact = quote?.optString("exact").orEmpty()
    val body = optJSONObject("body")
    return EpubAnnotation(
      id = optString("id").removePrefix("urn:uuid:"),
      kind = kind,
      locator = Locator(
        href = requireNotNull(Url(target.getString("source"))),
        mediaType = MediaType.XHTML,
        locations = Locator.Locations(progression = progression),
        text = Locator.Text(
          before = quote?.optString("prefix"),
          highlight = quote?.optString("exact"),
          after = quote?.optString("suffix")
        )
      ),
      text = exact,
      note = body?.optString("value"),
      created = optString("created").ifBlank { Instant.EPOCH.toString() }
    )
  }
}

/** Keeps a separate Readium `.annotation` sidecar in app-private storage; EPUB files are untouched. */
@Singleton
class EpubAnnotationRepository @Inject constructor(
  @ApplicationContext context: Context,
  @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
  private val directory = File(context.filesDir, "epub-annotations")
  private val writes = Mutex()

  suspend fun load(bookId: String): List<EpubAnnotation> = withContext(ioDispatcher) {
    val file = annotationFile(bookId)
    if (!file.isFile) return@withContext emptyList()
    EpubAnnotationSetCodec.decode(runCatching { file.readText() }.getOrDefault(""))
  }

  suspend fun save(bookId: String, title: String, annotations: List<EpubAnnotation>) =
    withContext(ioDispatcher) {
      writes.withLock {
        if (!directory.exists() && !directory.mkdirs()) return@withLock
        val file = annotationFile(bookId)
        val setId = if (file.isFile) {
          runCatching { JSONObject(file.readText()).optString("id") }.getOrNull()
        } else {
          null
        }.takeUnless(String?::isNullOrBlank) ?: "urn:uuid:${UUID.randomUUID()}"
        val temporary = File(directory, "${file.name}.tmp")
        temporary.writeText(EpubAnnotationSetCodec.encode(setId, title, annotations))
        if (!temporary.renameTo(file)) {
          temporary.copyTo(file, overwrite = true)
          temporary.delete()
        }
      }
    }

  @VisibleForTesting
  internal fun annotationFile(bookId: String): File {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(bookId.toByteArray())
      .joinToString("") { "%02x".format(it) }
    return File(directory, "$digest.annotation")
  }
}
