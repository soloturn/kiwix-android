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
 */

package org.kiwix.kiwixmobile.core.epub.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import java.io.File
import java.security.MessageDigest

/** Bounded decoded and encoded storage for EPUB page thumbnails. */
internal class EpubPreviewCache(cacheDir: File) {
  private val directory = File(cacheDir, "epub-previews-v1")
  private val memoryCache = object : LruCache<String, Bitmap>(MAX_MEMORY_BYTES) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
  }
  private var diskCacheBytes = 0L

  init {
    directory.listFiles()?.filter { it.name.endsWith(TEMP_SUFFIX) }?.forEach(File::delete)
    diskCacheBytes = directory.listFiles()
      ?.filter { it.isFile && isImageFile(it) }
      ?.sumOf(File::length) ?: 0L
    trimDiskCache()
  }

  @Synchronized
  fun get(key: String): Bitmap? {
    val bitmap = memoryCache.get(key) ?: decodeFromDisk(key)
    if (bitmap != null) {
      fileFor(key).setLastModified(System.currentTimeMillis())
      memoryCache.put(key, bitmap)
      trimMemoryCount()
    }
    return bitmap
  }

  private fun decodeFromDisk(key: String): Bitmap? {
    val file = fileFor(key)
    val encodedSize = file.length()
    val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
    val decoded = if (file.isFile) {
      runCatching { BitmapFactory.decodeFile(file.path, options) }.getOrNull()
    } else {
      null
    }
    if (decoded == null && file.delete()) diskCacheBytes -= encodedSize
    return decoded?.let {
      if (it.config == Bitmap.Config.RGB_565) {
        it
      } else {
        it.copy(Bitmap.Config.RGB_565, false)?.also { _ -> it.recycle() } ?: it
      }
    }
  }

  /** Takes ownership of [bitmap], storing an RGB_565 version in both caches. */
  @Synchronized
  fun put(key: String, bitmap: Bitmap) {
    val optimized = if (bitmap.config == Bitmap.Config.RGB_565) {
      bitmap
    } else {
      bitmap.copy(Bitmap.Config.RGB_565, false) ?: bitmap
    }
    if (optimized !== bitmap) bitmap.recycle()
    memoryCache.put(key, optimized)
    trimMemoryCount()
    writeToDisk(key, optimized)
  }

  private fun writeToDisk(key: String, bitmap: Bitmap) {
    if (!directory.exists() && !directory.mkdirs()) return

    val target = fileFor(key)
    if (target.isFile) {
      target.setLastModified(System.currentTimeMillis())
      return
    }
    val temporary = File(directory, "${target.name}-$TEMP_SUFFIX")
    val encoded = runCatching {
      temporary.outputStream().use { output ->
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
          Bitmap.CompressFormat.PNG
        }
        bitmap.compress(format, WEBP_EFFORT, output)
      }
    }.getOrDefault(false)
    if (encoded && trimDiskCache(temporary.length()) && temporary.renameTo(target)) {
      target.setLastModified(System.currentTimeMillis())
      diskCacheBytes += target.length()
    } else {
      temporary.delete()
    }
  }

  private fun fileFor(key: String): File = File(
    directory,
    key.sha256() + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) WEBP_SUFFIX else PNG_SUFFIX
  )

  private fun trimDiskCache(incomingBytes: Long = 0L): Boolean {
    if (diskCacheBytes + incomingBytes > MAX_DISK_BYTES) {
      val files = directory.listFiles()?.filter { it.isFile && isImageFile(it) } ?: return false
      for (file in files.sortedBy(File::lastModified)) {
        if (diskCacheBytes + incomingBytes <= MAX_DISK_BYTES) break
        val size = file.length()
        if (file.delete()) diskCacheBytes -= size
      }
    }
    return diskCacheBytes + incomingBytes <= MAX_DISK_BYTES
  }

  private fun isImageFile(file: File) =
    file.name.endsWith(WEBP_SUFFIX) || file.name.endsWith(PNG_SUFFIX)

  private fun trimMemoryCount() {
    while (memoryCache.snapshot().size > MAX_MEMORY_ENTRIES) {
      val leastRecentlyUsed = memoryCache.snapshot().keys.firstOrNull() ?: return
      memoryCache.remove(leastRecentlyUsed)
    }
  }

  private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray())
    .joinToString("") { "%02x".format(it) }

  private companion object {
    const val MAX_MEMORY_BYTES = 16 * 1024 * 1024
    const val MAX_MEMORY_ENTRIES = 27
    const val MAX_DISK_BYTES = 128L * 1024 * 1024
    const val WEBP_EFFORT = 20
    const val WEBP_SUFFIX = ".webp"
    const val PNG_SUFFIX = ".png"
    const val TEMP_SUFFIX = ".tmp"
  }
}
