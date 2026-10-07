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
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.view.Surface
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Captures a Readium preview display into GPU-backed buffers, then scales it with Vulkan. */
@RequiresApi(Build.VERSION_CODES.R)
internal class VulkanPreviewCapture(
  context: Context,
  width: Int,
  height: Int
) : AutoCloseable {
  private val closed = AtomicBoolean(false)
  private val handle = if (libraryLoaded && width > 0 && height > 0) {
    nativeCreate(width, height, context.applicationContext.assets)
  } else {
    0L
  }
  val surface: Surface? = if (handle != 0L) nativeSurface(handle) else null
  private val virtualDisplay: VirtualDisplay? = surface?.let {
    context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
      "Kiwix EPUB previews",
      width,
      height,
      context.resources.displayMetrics.densityDpi,
      it,
      DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
    )
  }
  val display = virtualDisplay?.display

  val isAvailable: Boolean
    get() = handle != 0L && surface != null && virtualDisplay != null && display != null

  fun frameSequence(): Long = if (isAvailable) nativeFrameSequence(handle) else -1L

  suspend fun captureAfter(
    sequence: Long,
    width: Int,
    height: Int,
    timeoutMillis: Long
  ): Bitmap? {
    if (!isAvailable || closed.get()) return null
    return withContext(Dispatchers.IO) {
      if (closed.get()) null else nativeCaptureAfter(handle, sequence, width, height, timeoutMillis)
    }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    virtualDisplay?.release()
    surface?.release()
    if (handle != 0L) nativeDestroy(handle)
  }

  private external fun nativeCreate(width: Int, height: Int, assets: AssetManager): Long
  private external fun nativeSurface(handle: Long): Surface?
  private external fun nativeFrameSequence(handle: Long): Long
  private external fun nativeCaptureAfter(
    handle: Long,
    sequence: Long,
    width: Int,
    height: Int,
    timeoutMillis: Long
  ): Bitmap?
  private external fun nativeDestroy(handle: Long)

  companion object {
    private val libraryLoaded = runCatching {
      System.loadLibrary("kiwix_epub_vulkan")
    }.isSuccess
  }
}
