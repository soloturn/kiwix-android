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
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class EpubAnnotationKind(val motivation: String) {
  BOOKMARK("bookmarking"),
  NOTE("commenting"),
  HIGHLIGHT("highlighting")
}

@Suppress("MagicNumber")
enum class EpubHighlightColor(
  val tint: Int,
  val hex: String,
  val darkTint: Int,
  val darkHex: String
) {
  LIGHT_YELLOW(0x66FFF59D, "#FFF59D", 0x997A6500.toInt(), "#7A6500"),
  LIGHT_GREEN(0x66A5D6A7, "#A5D6A7", 0x99587A3F.toInt(), "#587A3F"),
  LIGHT_BLUE(0x6690CAF9, "#90CAF9", 0x99516F99.toInt(), "#516F99"),
  LIGHT_PINK(0x66F48FB1, "#F48FB1", 0x99864E61.toInt(), "#864E61"),
  LIGHT_PURPLE(0x66CE93D8, "#CE93D8", 0x9973548B.toInt(), "#73548B");

  fun tintFor(darkTheme: Boolean) = if (darkTheme) darkTint else tint

  fun hexFor(darkTheme: Boolean) = if (darkTheme) darkHex else hex
}

/** One annotation in the Readium AnnotationSet, with its Readium locator for navigation. */
data class EpubAnnotation(
  val id: String,
  val kind: EpubAnnotationKind,
  val locator: Locator,
  val text: String,
  val note: String? = null,
  val created: String = Instant.now().toString(),
  val title: String = "",
  val color: EpubHighlightColor? = null
) {
  val label: String
    get() = when (kind) {
      EpubAnnotationKind.BOOKMARK -> "Bookmark"
      EpubAnnotationKind.NOTE -> note?.takeIf(String::isNotBlank) ?: text.ifBlank { "Note" }
      EpubAnnotationKind.HIGHLIGHT -> text.ifBlank { "Highlight" }
    }
}

internal fun EpubAnnotation.matchesSelection(selection: Locator): Boolean = when {
  locator.href != selection.href -> false
  kind == EpubAnnotationKind.BOOKMARK -> locator.sameBookmarkPosition(selection)
  else -> {
    val selectedText = selection.text.highlight.orEmpty().trim()
    val annotationText = text.ifBlank { locator.text.highlight.orEmpty() }.trim()
    selectedText.isNotEmpty() && annotationText.contains(selectedText, ignoreCase = true)
  }
}

/** Readium AnnotationSet JSON serialization and parsing for bookmark, note and highlight. */
object EpubAnnotationSetCodec {
  private const val CONTEXT = "http://www.w3.org/ns/anno.jsonld"
  private const val KIWIX_CONTEXT = "https://kiwix.org/ns/annotations#"

  fun encode(
    setId: String,
    title: String,
    annotations: List<EpubAnnotation>
  ): String = JSONObject()
    .put("@context", JSONArray().put(CONTEXT).put(JSONObject().put("kiwix", KIWIX_CONTEXT)))
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
    if (title.isNotBlank()) annotation.put("kiwix:title", title)
    color?.let { annotation.put("kiwix:color", it.name) }
    if (note != null || kind == EpubAnnotationKind.NOTE) {
      annotation.put("body", JSONObject().put("type", "TextualBody").put("value", note.orEmpty()))
    }
    return annotation
  }

  private fun JSONObject.toAnnotation(): EpubAnnotation? {
    val kind = when {
      // Older app versions saved passage favorites; keep them as navigable bookmarks.
      optString("kiwix:kind") == "favorite" -> EpubAnnotationKind.BOOKMARK
      optString("motivation") == "bookmarking" -> EpubAnnotationKind.BOOKMARK
      optString("motivation") == "commenting" -> EpubAnnotationKind.NOTE
      optString("motivation") in setOf("highlighting", "hightlighting") -> EpubAnnotationKind.HIGHLIGHT
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
      created = optString("created").ifBlank { Instant.EPOCH.toString() },
      title = optString("kiwix:title"),
      color = runCatching { EpubHighlightColor.valueOf(optString("kiwix:color")) }.getOrNull()
    )
  }
}

/** Keeps a separate Readium `.annotation` sidecar beside the EPUB; EPUB files are untouched. */
@Singleton
class EpubAnnotationRepository @Inject constructor(
  @ApplicationContext context: Context,
  @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
  private val legacyDirectory = File(context.filesDir, "epub-annotations")
  private val writes = Mutex()
  private val setIds = ConcurrentHashMap<String, String>()

  suspend fun load(bookId: String, epubFile: File): List<EpubAnnotation> = withContext(ioDispatcher) {
    val file = annotationFile(epubFile)
    if (!file.isFile) migrateLegacySidecar(bookId, file)
    if (!file.isFile) return@withContext emptyList()
    val content = runCatching { file.readText() }.getOrDefault("")
    runCatching { JSONObject(content).optString("id") }
      .getOrNull()
      ?.takeIf(String::isNotBlank)
      ?.let { setIds[bookId] = it }
    EpubAnnotationSetCodec.decode(content)
  }

  suspend fun save(
    bookId: String,
    title: String,
    annotations: List<EpubAnnotation>,
    epubFile: File
  ) =
    withContext(ioDispatcher) {
      writes.withLock {
        val file = annotationFile(epubFile)
        val parent = file.parentFile ?: return@withLock
        if (!parent.isDirectory && !parent.mkdirs()) return@withLock
        val setId = setIds[bookId] ?: run {
          runCatching { JSONObject(file.readText()).optString("id") }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: "urn:uuid:${UUID.randomUUID()}"
        }.also { setIds[bookId] = it }
        val temporary = File(parent, "${file.name}.tmp")
        temporary.writeText(EpubAnnotationSetCodec.encode(setId, title, annotations))
        if (!temporary.renameTo(file)) {
          temporary.copyTo(file, overwrite = true)
          temporary.delete()
        }
      }
    }

  @VisibleForTesting
  internal fun annotationFile(epubFile: File): File =
    File(epubFile.parentFile, "${epubFile.name}.annotation")

  private fun migrateLegacySidecar(bookId: String, destination: File) {
    val legacy = legacyAnnotationFile(bookId)
    if (!legacy.isFile) return
    runCatching {
      destination.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
      legacy.copyTo(destination, overwrite = false)
    }
  }

  private fun legacyAnnotationFile(bookId: String): File {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(bookId.toByteArray())
      .joinToString("") { "%02x".format(it) }
    return File(legacyDirectory, "$digest.annotation")
  }
}
