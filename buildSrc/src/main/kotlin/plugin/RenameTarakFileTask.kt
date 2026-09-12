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

package plugin

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import java.io.File
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Moves Tarask-dialect strings that no longer exist in the main strings.xml into a
 * `values-b+be+tarask+old` backup, so removed strings don't linger as lint errors.
 *
 * Backs up [trackedFiles] first, via [TrackedFileBackupAction] - defined as plain functions
 * rather than as a `doFirst {}` closure over `app/build.gradle.kts` script state, because any
 * closure referencing outer script state captures the whole script's `Project`, which the
 * configuration cache can't serialize. See [RestoreTrackedFilesFlowAction] for the matching
 * end-of-build restore.
 */
abstract class RenameTarakFileTask : DefaultTask() {
  @get:Internal
  abstract val coreResDir: DirectoryProperty

  @get:Internal
  abstract val trackedFiles: ListProperty<File>

  @get:Internal
  abstract val backupDir: DirectoryProperty

  @get:Internal
  abstract val repoRootDir: Property<File>

  @TaskAction
  fun run() {
    val backupDirFile = backupDir.get().asFile
    val rootDirFile = repoRootDir.get()
    trackedFiles.get().forEach { TrackedFileBackupAction.backup(it, backupDirFile, rootDirFile) }

    val resDir = coreResDir.get().asFile
    val taraskFile = File(resDir, "values-b+be+tarask/strings.xml")
    val mainStringsFile = File(resDir, "values/strings.xml")
    if (!taraskFile.exists() || !mainStringsFile.exists()) return

    val taraskOldFile = File(resDir, "values-b+be+tarask+old/strings.xml")
    if (!taraskOldFile.exists()) taraskOldFile.createNewFile()

    // Parse the main strings.xml file and extract the string tags
    val mainTags = getStringTags(mainStringsFile)

    // Parse the tarask file and filter strings based on tags present in the main strings file.
    // This ensures that any string removed from the main strings file will not be added to the
    // old file, and it prevents lint errors.
    val filteredContent = filterStringsByTags(taraskFile, mainTags)

    taraskOldFile.printWriter().use { writer ->
      writer.println("""<?xml version="1.0" encoding="utf-8"?>""")
      writer.println("<resources>")
      filteredContent.forEach { string -> writer.println("  $string") }
      writer.println("</resources>")
    }

    taraskFile.delete()
  }

  private fun getStringTags(file: File): Set<String> {
    val tags = mutableSetOf<String>()
    val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
    val nodeList = doc.getElementsByTagName("string")
    (0 until nodeList.length)
      .asSequence()
      .map { nodeList.item(it) as Element }
      .mapTo(tags) { it.getAttribute("name") }
    return tags
  }

  private fun filterStringsByTags(
    file: File,
    tags: Set<String>
  ): List<String> {
    val filteredStrings = mutableListOf<String>()
    val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
    val nodeList = doc.getElementsByTagName("string")
    for (i in 0 until nodeList.length) {
      val element = nodeList.item(i) as Element
      if (element.getAttribute("name") in tags) {
        filteredStrings.add(elementToString(element))
      }
    }
    return filteredStrings
  }

  private fun elementToString(element: Element): String {
    val transformer = TransformerFactory.newInstance().newTransformer().apply {
      setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
    }
    val result = StreamResult(StringWriter())
    transformer.transform(DOMSource(element), result)
    return result.writer.toString()
  }
}
