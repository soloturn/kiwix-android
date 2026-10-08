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

package org.kiwix.kiwixmobile.core.epub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kiwix.kiwixmobile.core.dao.EpubLibraryDao
import org.kiwix.kiwixmobile.core.dao.entities.EpubBookRoomEntity
import org.kiwix.kiwixmobile.core.epub.reader.EpubReaderActivity
import org.kiwix.kiwixmobile.core.epub.reader.EpubReaderViewModel
import org.kiwix.kiwixmobile.core.utils.KiwixPermissionChecker
import org.kiwix.kiwixmobile.core.utils.datastore.KiwixDataStore
import org.kiwix.kiwixmobile.core.utils.files.EpubImportResult
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R])
class EpubOpenUseCaseTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val dao = FakeDao()
  private val permissions: KiwixPermissionChecker = mockk()
  private val dataStore: KiwixDataStore = mockk()
  private lateinit var useCase: EpubOpenUseCase
  private var imported: EpubImportResult = EpubImportResult.Invalid
  private var importCalls = 0

  @Before
  fun setUp() {
    val dispatcher = StandardTestDispatcher()
    val manager = EpubLibraryManager(
      dao,
      mockk(relaxed = true),
      { EpubBookInfo("id-${it.name}", "Title", listOf("Author"), "en", null) },
      dispatcher
    )
    every { dataStore.isBrandedApp } returns flowOf(false)
    coEvery { permissions.hasReadExternalStoragePermission() } returns true
    useCase = EpubOpenUseCase(context, manager, permissions, dataStore, dispatcher).apply {
      importer = { _, _ ->
        importCalls++
        imported
      }
    }
    dispatcherForTest = dispatcher
  }

  private lateinit var dispatcherForTest: TestDispatcher

  private fun privateEpub(name: String = "a.epub") =
    File(File(context.filesDir, "epub").apply { mkdirs() }, name).also(::writeEpub)

  private fun externalEpub(name: String = "ext.epub") = tmp.newFile(name).also(::writeEpub)

  private fun writeEpub(file: File) {
    java.util.zip.ZipOutputStream(file.outputStream()).use {
      it.putNextEntry(java.util.zip.ZipEntry("mimetype"))
      it.write("application/epub+zip".toByteArray())
      it.closeEntry()
    }
  }

  private fun startedIntent(): Intent? = shadowOf(context as android.app.Application).nextStartedActivity

  private val uri: Uri = Uri.parse("content://provider/1")

  @Test
  fun `a file opens the reader and lands in the library as just opened`() = runTest(dispatcherForTest) {
    val file = privateEpub()

    val result = useCase.open(context, EpubSource.Path(file))

    assertEquals(EpubOpenResult.Ready(file, "id-a.epub"), result)
    val started = startedIntent()!!
    assertEquals(EpubReaderActivity::class.java.name, started.component?.className)
    assertEquals(file.path, started.getStringExtra(EpubReaderViewModel.EXTRA_PATH))
    assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    assertTrue(dao.rows.single().lastOpenedAt > 0)
  }

  @Test
  fun `a content uri is imported, then opened from the imported file`() = runTest(dispatcherForTest) {
    val file = privateEpub("imported.epub")
    imported = EpubImportResult.Imported(file)

    val result = useCase.open(context, EpubSource.Content(uri))

    assertEquals(EpubOpenResult.Ready(file, "id-imported.epub"), result)
    assertEquals(file.path, startedIntent()!!.getStringExtra(EpubReaderViewModel.EXTRA_PATH))
  }

  @Test
  fun `a too large book says so and does not open`() = runTest(dispatcherForTest) {
    imported = EpubImportResult.TooLarge(size = 600L * MB, max = 512L * MB)

    val result = useCase.open(context, EpubSource.Content(uri))

    val message = (result as EpubOpenResult.Failed).message
    assertTrue(message, message.contains("too large"))
    assertEquals(message, ShadowToast.getTextOfLatestToast())
    assertNull(startedIntent())
    assertTrue(dao.rows.isEmpty())
  }

  @Test
  fun `not enough space says so and does not open`() = runTest(dispatcherForTest) {
    imported = EpubImportResult.NotEnoughSpace(required = 90L * MB, available = 10L * MB)

    val result = useCase.open(context, EpubSource.Content(uri))

    val message = (result as EpubOpenResult.Failed).message
    assertTrue(message, message.contains("free space"))
    assertEquals(message, ShadowToast.getTextOfLatestToast())
    assertNull(startedIntent())
  }

  @Test
  fun `an invalid import reports the generic failure`() = runTest(dispatcherForTest) {
    imported = EpubImportResult.Invalid

    val result = useCase.open(context, EpubSource.Content(uri))

    assertEquals(
      EpubOpenResult.Failed(context.getString(org.kiwix.kiwixmobile.core.R.string.epub_open_failed)),
      result
    )
    assertNotNull(ShadowToast.getLatestToast())
    assertNull(startedIntent())
  }

  @Test
  fun `a missing or unparseable file fails instead of opening`() = runTest(dispatcherForTest) {
    val missing = File(context.filesDir, "gone.epub")
    val unreadable = privateEpub("junk.epub")
    val failing = EpubOpenUseCase(
      context,
      EpubLibraryManager(dao, mockk(relaxed = true), { null }, dispatcherForTest),
      permissions,
      dataStore,
      dispatcherForTest
    )

    assertTrue(useCase.open(context, EpubSource.Path(missing)) is EpubOpenResult.Failed)
    assertTrue(failing.open(context, EpubSource.Path(unreadable)) is EpubOpenResult.Failed)
    assertNull(startedIntent())
  }

  @Test
  fun `an outside file without read permission asks the caller for it`() = runTest(dispatcherForTest) {
    val file = externalEpub()
    coEvery { permissions.hasReadExternalStoragePermission() } returns false

    val result = useCase.open(context, EpubSource.Path(file))

    assertEquals(EpubOpenResult.NeedsStoragePermission(file), result)
    assertNull(startedIntent())

    coEvery { permissions.hasReadExternalStoragePermission() } returns true
    assertTrue(useCase.open(context, EpubSource.Path(file)) is EpubOpenResult.Ready)
    assertNotNull(startedIntent())
  }

  @Test
  fun `app private files and branded apps never need the permission`() = runTest(dispatcherForTest) {
    coEvery { permissions.hasReadExternalStoragePermission() } returns false

    assertTrue(useCase.open(context, EpubSource.Path(privateEpub())) is EpubOpenResult.Ready)

    every { dataStore.isBrandedApp } returns flowOf(true)
    assertTrue(useCase.open(context, EpubSource.Path(externalEpub())) is EpubOpenResult.Ready)
  }

  @Test
  fun `opening the same file or the same import twice keeps one library entry`() =
    runTest(dispatcherForTest) {
      val file = privateEpub("same.epub")
      imported = EpubImportResult.Imported(file)

      useCase.open(context, EpubSource.Path(file))
      useCase.open(context, EpubSource.Content(uri))
      useCase.open(context, EpubSource.Content(uri))

      assertEquals(3, generateSequence { startedIntent() }.count())
      assertEquals(1, dao.rows.size)
      assertEquals(2, importCalls)
    }

  @Test
  fun `prepare adds without opening or toasting, and only stamps when asked`() =
    runTest(dispatcherForTest) {
      val file = privateEpub()

      val result = useCase.prepare(EpubSource.Path(file), markOpened = false)
      useCase.prepare(EpubSource.Content(uri), markOpened = false)

      assertTrue(result is EpubOpenResult.Ready)
      assertEquals(0L, dao.rows.single().lastOpenedAt)
      assertNull(startedIntent())
      assertNull(ShadowToast.getLatestToast())
    }

  private class FakeDao : EpubLibraryDao() {
    val rows = mutableListOf<EpubBookRoomEntity>()
    override fun epubs(): Flow<List<EpubBookRoomEntity>> = flowOf(rows.toList())
    override fun getById(id: String) = rows.firstOrNull { it.id == id }
    override fun getByPath(path: String) = rows.firstOrNull { it.path == path }
    override fun allPaths() = rows.map { it.path }
    override fun upsert(entity: EpubBookRoomEntity) {
      rows.removeAll { it.id == entity.id || it.path == entity.path }
      rows += entity
    }

    override fun markOpened(id: String, openedAt: Long) {
      rows.replaceAll { if (it.id == id) it.copy(lastOpenedAt = openedAt) else it }
    }

    override fun getLocator(id: String) = rows.firstOrNull { it.id == id }?.lastLocator

    override fun setLocator(id: String, locatorJson: String?) {
      rows.replaceAll { if (it.id == id) it.copy(lastLocator = locatorJson) else it }
    }

    override fun delete(id: String) {
      rows.removeAll { it.id == id }
    }
  }

  private companion object {
    const val MB = 1024L * 1024
  }
}
