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
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kiwix.kiwixmobile.core.dao.entities.EpubBookRoomEntity
import org.kiwix.kiwixmobile.core.data.KiwixRoomDatabase
import org.kiwix.sharedFunctions.TestApplication
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R], application = TestApplication::class)
class EpubLibraryDaoTest {
  private lateinit var db: KiwixRoomDatabase
  private lateinit var dao: EpubLibraryDao

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, KiwixRoomDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    dao = db.epubLibraryDao()
  }

  @After
  fun tearDown() {
    db.close()
    KiwixRoomDatabase.destroyInstance()
  }

  @Test
  fun upsertThenQuery_roundTripsAllFields() = runTest {
    val entity = entity("id1", "/a/one.epub", cover = "/c/1.jpg", size = 42L)
    dao.upsert(entity)
    assertThat(dao.getById("id1")).isEqualTo(entity)
    assertThat(dao.getByPath("/a/one.epub")).isEqualTo(entity)
  }

  @Test
  fun upsert_sameId_replaces() = runTest {
    dao.upsert(entity("id1", "/a/one.epub", title = "Old"))
    dao.upsert(entity("id1", "/a/one.epub", title = "New"))
    dao.epubs().test {
      assertThat(awaitItem().map { it.title }).containsExactly("New")
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun upsert_samePathDifferentId_replacesThroughUniqueIndex() = runTest {
    dao.upsert(entity("id1", "/a/one.epub"))
    dao.upsert(entity("id2", "/a/one.epub"))
    assertThat(dao.allPaths()).containsExactly("/a/one.epub")
    assertThat(dao.getById("id1")).isNull()
  }

  @Test
  fun epubs_ordersByMostRecentActivityThenTitle() = runTest {
    dao.upsert(entity("b", "/b.epub", title = "Bravo", addedAt = 10))
    dao.upsert(entity("a", "/a.epub", title = "alpha", addedAt = 10))
    dao.upsert(entity("c", "/c.epub", title = "Charlie", addedAt = 5, lastOpenedAt = 50))
    dao.epubs().test {
      assertThat(awaitItem().map { it.id }).containsExactly("c", "a", "b")
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun markOpened_updatesOnlyLastOpenedAt() = runTest {
    dao.upsert(entity("id1", "/a.epub", addedAt = 1))
    dao.markOpened("id1", 99)
    val updated = dao.getById("id1")!!
    assertThat(updated.lastOpenedAt).isEqualTo(99)
    assertThat(updated.addedAt).isEqualTo(1)
  }

  @Test
  fun delete_byIdAndByPath() = runTest {
    dao.upsert(entity("id1", "/a.epub"))
    dao.upsert(entity("id2", "/b.epub"))
    dao.delete("id1")
    assertThat(dao.allPaths()).containsExactly("/b.epub")
    dao.deleteByPath("/b.epub")
    assertThat(dao.allPaths()).isEmpty()
  }

  private fun entity(
    id: String,
    path: String,
    title: String = "Title",
    cover: String? = null,
    size: Long = 1L,
    addedAt: Long = 0L,
    lastOpenedAt: Long = 0L
  ) = EpubBookRoomEntity(
    id = id,
    path = path,
    title = title,
    authors = "A. Author",
    language = "en",
    coverPath = cover,
    size = size,
    addedAt = addedAt,
    lastOpenedAt = lastOpenedAt
  )
}
