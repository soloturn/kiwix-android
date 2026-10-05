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

package org.kiwix.kiwixmobile.core.utils.files

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class UserDataFilesTest {
  @TempDir
  lateinit var tempDir: File

  private val context: Context = mockk()
  private lateinit var internalDir: File
  private lateinit var externalDir: File

  @BeforeEach
  fun setUp() {
    internalDir = File(tempDir, "internal").apply { mkdirs() }
    externalDir = File(tempDir, "external").apply { mkdirs() }
    every { context.filesDir } returns internalDir
    every { context.getExternalFilesDir(null) } returns externalDir
    every { context.getExternalFilesDir("") } returns externalDir
  }

  @Test
  fun `internal files live under filesDir`() {
    val file = UserDataFiles.internalFile(context, UserDataFiles.BOOKMARKS_DIR, "bookmark.xml")
    assertThat(file).isEqualTo(File(internalDir, "Bookmarks/bookmark.xml"))
  }

  @Test
  fun `legacy external file is the migration source when no internal copy exists`() {
    val legacy = File(externalDir, "Bookmarks/bookmark.xml").apply {
      parentFile?.mkdirs()
      writeText("<bookmarks/>")
    }
    assertThat(
      UserDataFiles.migrationSource(context, UserDataFiles.BOOKMARKS_DIR, "bookmark.xml")
    ).isEqualTo(legacy)
  }

  @Test
  fun `emulator legacy file in filesDir root is found`() {
    val legacy = File(internalDir, "library.xml").apply { writeText("<library/>") }
    assertThat(
      UserDataFiles.migrationSource(context, UserDataFiles.LOCAL_LIBRARY_DIR, "library.xml")
    ).isEqualTo(legacy)
  }

  @Test
  fun `no migration once the internal file exists`() {
    File(externalDir, "ZIMFiles/library.xml").apply {
      parentFile?.mkdirs()
      writeText("<library/>")
    }
    UserDataFiles.internalFile(context, UserDataFiles.LOCAL_LIBRARY_DIR, "library.xml").apply {
      parentFile?.mkdirs()
      writeText("<library/>")
    }
    assertThat(
      UserDataFiles.migrationSource(context, UserDataFiles.LOCAL_LIBRARY_DIR, "library.xml")
    ).isNull()
  }

  @Test
  fun `empty legacy files are ignored`() {
    File(externalDir, "ZIMFiles/library.xml").apply {
      parentFile?.mkdirs()
      createNewFile()
    }
    assertThat(
      UserDataFiles.migrationSource(context, UserDataFiles.LOCAL_LIBRARY_DIR, "library.xml")
    ).isNull()
  }

  @Test
  fun `notes move from external to internal storage`() {
    assertThat(UserDataFiles.notesDirectory(context)).isEqualTo("$internalDir/Kiwix/Notes/")
    assertThat(UserDataFiles.legacyNotesDirectory(context)).isEqualTo("$externalDir/Kiwix/Notes/")
  }
}
