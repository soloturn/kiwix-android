package org.kiwix.kiwixmobile.core.main.reader

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.kiwix.kiwixmobile.core.di.IoDispatcher
import org.kiwix.kiwixmobile.core.epub.reader.EpubHighlightColor
import org.kiwix.kiwixmobile.core.reader.ZimReaderSource
import org.kiwix.kiwixmobile.core.utils.files.saf.DocumentTree
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class ZimHighlight(
  val id: String,
  val pageUrl: String,
  val text: String,
  val prefix: String,
  val suffix: String,
  val color: EpubHighlightColor
)

object ZimHighlightCodec {
  fun encode(bookId: String, title: String, highlights: List<ZimHighlight>): String =
    JSONObject()
      .put("bookId", bookId)
      .put("title", title)
      .put(
        "highlights",
        JSONArray().apply {
          highlights.forEach { highlight ->
            put(
              JSONObject()
                .put("id", highlight.id)
                .put("pageUrl", highlight.pageUrl)
                .put("text", highlight.text)
                .put("prefix", highlight.prefix)
                .put("suffix", highlight.suffix)
                .put("color", highlight.color.name)
            )
          }
        }
      )
      .toString(2)

  fun decode(json: String): List<ZimHighlight> = runCatching {
    val items = JSONObject(json).getJSONArray("highlights")
    (0 until items.length()).mapNotNull { index ->
      runCatching {
        val item = items.getJSONObject(index)
        ZimHighlight(
          id = item.getString("id"),
          pageUrl = item.getString("pageUrl"),
          text = item.getString("text"),
          prefix = item.optString("prefix"),
          suffix = item.optString("suffix"),
          color = EpubHighlightColor.valueOf(item.getString("color"))
        )
      }.getOrNull()
    }
  }.getOrDefault(emptyList())
}

/** Stores ZIM highlights beside the archive, leaving the downloaded archive untouched. */
@Singleton
class ZimHighlightRepository @Inject constructor(
  @ApplicationContext context: Context,
  @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
  private val resolver = context.contentResolver
  private val legacyDirectory = File(context.filesDir, "zim-annotations")
  private val documentTree = DocumentTree(resolver)
  private val writes = Mutex()

  suspend fun load(bookId: String, source: ZimReaderSource?): List<ZimHighlight> =
    withContext(ioDispatcher) {
      val location = location(source, create = false)
      val content = location?.let(::read) ?: legacyAnnotationFile(bookId).let { legacy ->
        runCatching { legacy.takeIf(File::isFile)?.readText() }.getOrNull()
          ?.also { oldContent -> location(source, create = true)?.let { write(it, oldContent) } }
      }
      content?.let { runCatching { ZimHighlightCodec.decode(it) }.getOrDefault(emptyList()) }
        .orEmpty()
    }

  suspend fun add(
    bookId: String,
    title: String,
    highlight: ZimHighlight,
    source: ZimReaderSource?
  ) = update(bookId, title, source) { current ->
    current.filterNot { it.id == highlight.id } + highlight
  }

  suspend fun remove(bookId: String, title: String, ids: Set<String>, source: ZimReaderSource?) =
    update(bookId, title, source) { current -> current.filterNot { it.id in ids } }

  private suspend fun update(
    bookId: String,
    title: String,
    source: ZimReaderSource?,
    transform: (List<ZimHighlight>) -> List<ZimHighlight>
  ) = withContext(ioDispatcher) {
    writes.withLock {
      val target = location(source, create = true) ?: return@withLock
      val oldContent = read(target)?.takeIf(String::isNotBlank) ?: legacyAnnotationFile(bookId)
        .takeIf(File::isFile)?.let { runCatching { it.readText() }.getOrNull() }
      val current = oldContent?.let(ZimHighlightCodec::decode).orEmpty()
      val content = ZimHighlightCodec.encode(bookId, title, transform(current))
      if (!write(target, content)) Log.e(TAG, "Could not save ZIM annotations for $bookId")
    }
  }

  @VisibleForTesting
  internal fun annotationFile(source: ZimReaderSource): File? = source.file?.let { book ->
    File(book.parentFile, "${book.name}.annotation")
  }

  private fun location(source: ZimReaderSource?, create: Boolean): Location? = when {
    source == null -> null
    source.file != null -> annotationFile(source)?.let(Location::FileLocation)
    else -> source.uri?.takeIf(DocumentTree::isDocumentInTree)?.let { uri ->
      location(uri, create)
    }
  }

  private fun location(uri: Uri, create: Boolean): Location? {
    val sidecarName = documentTree.stat(uri)?.name?.let { "$it.annotation" } ?: return null
    val existing = documentTree.findSibling(uri, sidecarName)?.uri
    val sidecar = existing ?: if (create) {
      documentTree.createSibling(uri, sidecarName, ANNOTATION_MIME_TYPE)
    } else {
      null
    }
    return sidecar?.let(Location::ContentLocation)
  }

  private fun read(location: Location): String? = runCatching {
    when (location) {
      is Location.FileLocation -> location.file.takeIf(File::isFile)?.readText()
      is Location.ContentLocation -> resolver.openInputStream(location.uri)
        ?.bufferedReader()?.use { it.readText() }
    }
  }.getOrNull()

  private fun write(location: Location, content: String): Boolean = runCatching {
    when (location) {
      is Location.FileLocation -> {
        val file = location.file
        val parent = file.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        val temporary = File(parent, "${file.name}.tmp")
        temporary.writeText(content)
        if (!temporary.renameTo(file)) {
          temporary.copyTo(file, overwrite = true)
          temporary.delete()
        }
      }
      is Location.ContentLocation -> resolver.openOutputStream(location.uri, "wt")
        ?.bufferedWriter()?.use { it.write(content) } ?: return false
    }
    true
  }.getOrDefault(false)

  private fun legacyAnnotationFile(bookId: String): File {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(bookId.toByteArray())
      .joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }
    return File(legacyDirectory, "$digest.annotation")
  }

  private sealed interface Location {
    data class FileLocation(val file: File) : Location
    data class ContentLocation(val uri: Uri) : Location
  }

  companion object {
    private const val TAG = "ZimHighlightRepository"
    private const val ANNOTATION_MIME_TYPE = "application/json"
    private const val BYTE_MASK = 0xff
  }
}
