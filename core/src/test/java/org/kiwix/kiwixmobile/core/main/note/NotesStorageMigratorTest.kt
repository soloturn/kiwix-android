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

package org.kiwix.kiwixmobile.core.main.note

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kiwix.kiwixmobile.core.dao.NotesRoomDao
import org.kiwix.kiwixmobile.core.data.KiwixRoomDatabase
import org.kiwix.kiwixmobile.core.data.KiwixRoomDatabaseTest.Companion.getNoteListItem
import org.kiwix.kiwixmobile.core.page.notes.models.NoteListItem
import org.kiwix.sharedFunctions.MainDispatcherRule
import org.kiwix.sharedFunctions.TestApplication
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R], application = TestApplication::class)
class NotesStorageMigratorTest {
  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  @get:Rule
  val tempFolder = TemporaryFolder()

  private lateinit var database: KiwixRoomDatabase
  private lateinit var notesRoomDao: NotesRoomDao
  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    database = Room.inMemoryDatabaseBuilder(context, KiwixRoomDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    notesRoomDao = database.notesRoomDao()
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun movesNoteFilesAndRewritesStoredPaths() = runTest(mainDispatcherRule.dispatcher) {
    val legacyDir = File(tempFolder.root, "external/Kiwix/Notes/")
    val newDir = File(tempFolder.root, "internal/Kiwix/Notes/")
    val legacyNote = File(legacyDir, "Alpine/MainPage.txt").apply {
      parentFile?.mkdirs()
      writeText("my note")
    }
    notesRoomDao.saveNote(
      getNoteListItem(title = "A", zimUrl = "http://a", noteFilePath = legacyNote.path)
    )
    notesRoomDao.saveNote(
      getNoteListItem(title = "B", zimUrl = "http://b", noteFilePath = "/elsewhere/Other.txt")
    )

    NotesStorageMigrator(context, notesRoomDao, mainDispatcherRule.dispatcher)
      .migrate("${legacyDir.path}/", "${newDir.path}/")

    val migratedNote = File(newDir, "Alpine/MainPage.txt")
    assertEquals("my note", migratedNote.readText())
    assertFalse(legacyDir.exists())
    val paths = notesRoomDao.notes().first().map { (it as NoteListItem).noteFilePath }.toSet()
    assertEquals(setOf(migratedNote.path, "/elsewhere/Other.txt"), paths)
  }

  @Test
  fun keepsExistingInternalNoteOnConflict() = runTest(mainDispatcherRule.dispatcher) {
    val legacyDir = File(tempFolder.root, "external/Kiwix/Notes/")
    val newDir = File(tempFolder.root, "internal/Kiwix/Notes/")
    File(legacyDir, "A/page.txt").apply {
      parentFile?.mkdirs()
      writeText("old")
    }
    val existing = File(newDir, "A/page.txt").apply {
      parentFile?.mkdirs()
      writeText("new")
    }

    NotesStorageMigrator(context, notesRoomDao, mainDispatcherRule.dispatcher)
      .migrate("${legacyDir.path}/", "${newDir.path}/")

    assertEquals("new", existing.readText())
    assertFalse(legacyDir.exists())
  }

  @Test
  fun noLegacyDirectoryIsANoOp() = runTest(mainDispatcherRule.dispatcher) {
    val newDir = File(tempFolder.root, "internal/Kiwix/Notes/")
    NotesStorageMigrator(context, notesRoomDao, mainDispatcherRule.dispatcher)
      .migrate(File(tempFolder.root, "missing").path, newDir.path)
    assertFalse(newDir.exists())
    assertTrue(notesRoomDao.notes().first().isEmpty())
  }
}
