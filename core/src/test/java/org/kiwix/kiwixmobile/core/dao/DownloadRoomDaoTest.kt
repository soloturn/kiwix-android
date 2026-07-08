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

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.tonyodev.fetch2.Status
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kiwix.kiwixmobile.core.dao.entities.DownloadRoomEntity
import org.kiwix.kiwixmobile.core.dao.entities.PauseReason
import org.kiwix.kiwixmobile.core.data.KiwixRoomDatabase
import org.kiwix.kiwixmobile.core.downloader.DownloadRequester
import org.kiwix.kiwixmobile.core.entity.LibkiwixBook
import org.kiwix.sharedFunctions.TestApplication
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R], application = TestApplication::class)
class DownloadRoomDaoTest {
  private lateinit var kiwixRoomDatabase: KiwixRoomDatabase
  private lateinit var downloadRoomDao: DownloadRoomDao

  @Before
  fun setup() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    kiwixRoomDatabase = Room.inMemoryDatabaseBuilder(context, KiwixRoomDatabase::class.java)
      .allowMainThreadQueries()
      .build()

    downloadRoomDao = kiwixRoomDatabase.downloadRoomDao()
  }

  @After
  fun tearDown() {
    kiwixRoomDatabase.close()
    unmockkAll()
  }

  @Test
  fun `savesDownloads saves multiple download`() = runTest {
    val entity1 = downloadRoomEntity(downloadId = 1, bookId = "book1")
    val entity2 = downloadRoomEntity(downloadId = 2, bookId = "book2")

    downloadRoomDao.getAllDownloads().test {
      assertThat(awaitItem()).isEmpty()

      downloadRoomDao.saveDownload(entity1)
      val item1 = awaitItem()
      assertEquals(entity1.downloadId, item1[0].downloadId)

      downloadRoomDao.saveDownload(entity2)
      val item2 = awaitItem()
      assertEquals(entity2.downloadId, item2[1].downloadId)

      assertEquals(2, item2.size)

      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun `addIfDoesNotExist saves download when it does not already exist`() = runTest {
    val book = LibkiwixBook(_id = "new_book")
    val downloadRequester = mockk<DownloadRequester>()
    coEvery { downloadRequester.enqueue(any()) } returns 123L

    // Add first time
    downloadRoomDao.addIfDoesNotExist("url", book, downloadRequester)
    assertEquals(1, downloadRoomDao.count("new_book"))
    assertEquals(123L, downloadRoomDao.getEntityForDownloadId(123)?.downloadId)

    // Adding again should not call enqueue or save again
    downloadRoomDao.addIfDoesNotExist("url", book, downloadRequester)
    coVerify(exactly = 1) { downloadRequester.enqueue(any()) }
    assertEquals(1, downloadRoomDao.count("new_book"))
  }

  @Test
  fun `updateDownloadItem updates existing entity`() = runTest {
    val entity = downloadRoomEntity(downloadId = 1, title = "Original title")

    downloadRoomDao.saveDownload(entity)

    val savedEntity = downloadRoomDao.getEntityForDownloadId(1)
    assertThat(savedEntity).isNotNull
    val updatedEntity = savedEntity!!.copy(title = "Updated Title")

    downloadRoomDao.updateDownloadItem(updatedEntity)

    val result = downloadRoomDao.getEntityForDownloadId(1)
    assertThat(result).isNotNull
    assertEquals("Updated Title", result!!.title)
  }

  @Test
  fun `deleteDownloadsList removes all downloads in the list`() = runTest {
    val entity1 = downloadRoomEntity(downloadId = 1, bookId = "book1")
    val entity2 = downloadRoomEntity(downloadId = 2, bookId = "book2")

    val savedDownloadList = saveTestDownloads(listOf(entity1, entity2))

    // Before Deletion
    val getDownloadList = downloadRoomDao.getAllDownloads().first()
    assertEquals(savedDownloadList.size, getDownloadList.size)

    downloadRoomDao.deleteDownloadsList(getDownloadList)

    // After Deletion
    val currentList = downloadRoomDao.getAllDownloads().first()
    assertEquals(0, currentList.size)
  }

  @Test
  fun `deleteDownloadByDownloadId removes only the specific download`() = runTest {
    val entity1 = downloadRoomEntity(downloadId = 1, bookId = "book1")
    val entity2 = downloadRoomEntity(downloadId = 2, bookId = "book2")

    saveTestDownloads(listOf(entity1, entity2))

    downloadRoomDao.deleteDownloadByDownloadId(1)

    val downloads = downloadRoomDao.getAllDownloads().first()
    assertEquals(1, downloads.size)
    assertEquals(2, downloads[0].downloadId)
  }

  @Test
  fun `getEntityForDownloadId returns the correct entity`() = runTest {
    val entity1 = downloadRoomEntity(downloadId = 22, bookId = "book1")
    val entity2 = downloadRoomEntity(downloadId = 23, bookId = "book2")

    saveTestDownloads(listOf(entity1, entity2))

    val result = downloadRoomDao.getEntityForDownloadId(22)

    assertNotNull(result)
    assertEquals(entity1.downloadId, result!!.downloadId)
    assertEquals(entity1.bookId, result.bookId)
  }

  @Test
  fun `count returns the correct number of downloads for a bookId`() = runTest {
    val entity1 = downloadRoomEntity(downloadId = 1, bookId = "book1")
    val entity2 = downloadRoomEntity(downloadId = 2, bookId = "book1")
    val entity3 = downloadRoomEntity(downloadId = 3, bookId = "book2")

    saveTestDownloads(listOf(entity1, entity2, entity3))

    assertEquals(2, downloadRoomDao.count("book1"))
    assertEquals(1, downloadRoomDao.count("book2"))
    assertEquals(0, downloadRoomDao.count("book3"))
  }

  @Test
  fun `getEntityForFileName returns the correct entity using like match`() = runTest {
    val entity = downloadRoomEntity(file = "/storage/emulated/0/test.zim")
    downloadRoomDao.saveDownload(entity)

    val result = downloadRoomDao.getEntityForFileName("test")
    assertNotNull(result)
    assertEquals("/storage/emulated/0/test.zim", result!!.file)
  }

  @Test
  fun `getDownloadsPausedByService returns correctly filtered downloads`() = runTest {
    val entity1 = downloadRoomEntity(downloadId = 1, pauseReason = PauseReason.SERVICE)
    val entity2 = downloadRoomEntity(downloadId = 2, pauseReason = PauseReason.USER)

    saveTestDownloads(listOf(entity1, entity2))

    val pausedByService = downloadRoomDao.getDownloadsPausedByService()
    assertEquals(1, pausedByService.size)
    assertEquals(1, pausedByService[0].downloadId)
  }

  @Test
  fun `getOngoingDownloads returns only ongoing downloads`() = runTest {
    val queued = downloadRoomEntity(downloadId = 1, status = Status.QUEUED)
    val completed = downloadRoomEntity(downloadId = 2, status = Status.COMPLETED)
    val failed = downloadRoomEntity(downloadId = 3, status = Status.FAILED)
    val downloading = downloadRoomEntity(downloadId = 4, status = Status.DOWNLOADING)

    saveTestDownloads(listOf(queued, completed, failed, downloading))

    val ongoing = downloadRoomDao.getOngoingDownloads()
    assertEquals(2, ongoing.size)
    val ids = ongoing.map { it.downloadId }
    assertThat(ids).containsExactlyInAnyOrder(1, 4)
  }

  private fun downloadRoomEntity(
    downloadId: Long = 1,
    bookId: String = "bookId",
    title: String = "title",
    status: Status = Status.NONE,
    file: String = "file",
    pauseReason: PauseReason = PauseReason.NONE
  ): DownloadRoomEntity =
    DownloadRoomEntity(
      downloadId = downloadId,
      bookId = bookId,
      title = title,
      description = "description",
      language = "en",
      creator = "creator",
      publisher = "publisher",
      date = "2024",
      url = "url",
      articleCount = "100",
      mediaCount = "10",
      size = "100MB",
      name = "name",
      favIcon = "favicon",
      status = status,
      file = file,
      pauseReason = pauseReason
    )

  private fun saveTestDownloads(entityList: List<DownloadRoomEntity>): List<DownloadRoomEntity> {
    entityList.forEach { downloadRoomDao.saveDownload(it) }
    return entityList
  }
}
