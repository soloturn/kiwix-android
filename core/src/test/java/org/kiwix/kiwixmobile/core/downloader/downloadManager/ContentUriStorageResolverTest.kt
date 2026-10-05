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

package org.kiwix.kiwixmobile.core.downloader.downloadManager

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.tonyodev.fetch2core.Downloader
import com.tonyodev.fetch2core.getOutputResourceWrapper
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kiwix.sharedFunctions.TestApplication
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R], application = TestApplication::class)
class ContentUriStorageResolverTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val uri: Uri = Uri.parse("content://$AUTHORITY/wiki.zim.part")
  private val payload = ByteArray(PAYLOAD_SIZE) { (it % 251).toByte() }
  private lateinit var backingFile: File
  private lateinit var resolver: ContentUriStorageResolver

  @Before
  fun setUp() {
    backingFile = tempFolder.newFile("wiki.zim.part")
    FileBackedProvider.file = backingFile
    FileBackedProvider.modes.clear()
    Robolectric.setupContentProvider(FileBackedProvider::class.java, AUTHORITY)
    resolver = ContentUriStorageResolver(context, tempFolder.newFolder("tmp").path)
  }

  @Test
  fun killedDownloadResumesWithoutLosingItsPrefix() {
    val half = payload.size / 2
    // First attempt: Fetch checks, opens at offset 0 and writes half before the app is killed.
    assertTrue(resolver.fileExists("$uri"))
    assertEquals("$uri", resolver.createFile("$uri", false))
    resolver.getRequestOutputResourceWrapper(serverRequest()).use { output ->
      output.setWriteOffset(0)
      output.write(payload, 0, half)
      output.flush()
    }
    // Resume: Fetch reopens the target and seeks to the bytes it already has.
    resolver.getRequestOutputResourceWrapper(serverRequest()).use { output ->
      output.setWriteOffset(half.toLong())
      output.write(payload, half, payload.size - half)
      output.flush()
    }

    assertArrayEquals(payload, backingFile.readBytes())
    assertEquals(listOf("rw", "rw"), FileBackedProvider.modes.filter { it != "r" })
  }

  @Test
  fun fetchDefaultOpensWithTruncatingMode() {
    // Why the custom resolver exists: Fetch's own helper opens content URIs with "w", which
    // device providers (ExternalStorageProvider, MediaProvider) open with O_TRUNC, so every
    // resume would wipe the bytes already downloaded. Robolectric does not truncate, so only
    // the requested mode is checked here.
    getOutputResourceWrapper("$uri", context.contentResolver).close()
    assertEquals(listOf("w"), FileBackedProvider.modes)
  }

  @Test
  fun filePathsStillUseTheDefaultResolver() {
    val target = File(tempFolder.root, "Kiwix/plain.zim").path
    val created = resolver.createFile(target, false)
    assertTrue(File(created).exists())
    assertTrue(resolver.deleteFile(created))
    assertFalse(ContentUriStorageResolver.isContentUri(target))
    assertTrue(ContentUriStorageResolver.isContentUri("$uri"))
  }

  private fun serverRequest(): Downloader.ServerRequest =
    mockk { every { file } returns "$uri" }

  class FileBackedProvider : ContentProvider() {
    companion object {
      lateinit var file: File
      val modes = mutableListOf<String>()
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
      modes += mode
      return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun query(
      uri: Uri,
      projection: Array<out String>?,
      selection: String?,
      selectionArgs: Array<out String>?,
      sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(
      uri: Uri,
      values: ContentValues?,
      selection: String?,
      selectionArgs: Array<out String>?
    ) = 0
  }

  companion object {
    private const val AUTHORITY = "org.kiwix.test.files"
    private const val PAYLOAD_SIZE = 64 * 1024
  }
}
