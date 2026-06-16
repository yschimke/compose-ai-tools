package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.discovery.PreviewInfo
import ee.schimke.composeai.discovery.PreviewManifest
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins that [BundlePreviewTask] packs the module's own bytecode from AGP's scoped `PROJECT`
 * `CLASSES` artifact — wired by the Android backend into [BundlePreviewTask.projectClassDirs] /
 * [BundlePreviewTask.projectClassJars] — not just from the hardcoded `moduleClassDirs` directory
 * list.
 *
 * Discovery already resolves previews from the scoped artifact (issue #1924), so without this the
 * bundle's class set could be strictly narrower than the `previews.json` discovery wrote: a preview
 * whose class lands only in a scoped element (e.g. AGP 9.x built-in Kotlin output, which never
 * reaches `build/tmp/kotlin-classes/<variant>`) would be listed in the manifest but missing from
 * `classes/app.jar`, breaking detached rendering of that preview (issue #1926).
 *
 * The test drives the task action directly (no AGP/KGP) using a real compiled class off the test
 * classpath as a stand-in module "preview" class, with `moduleClassDirs` deliberately empty so the
 * scoped wiring is the only thing that can carry the class into the bundle.
 */
class BundleScopedClassesTest {

  @get:Rule val tmp = TemporaryFolder()

  private val json = Json {
    classDiscriminator = "kind"
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  // A real compiled class guaranteed to be on the test runtime classpath as a directory entry.
  private val previewClassFqn = "ee.schimke.composeai.plugin.BundlePreviewIds"
  private val previewClassRel = previewClassFqn.replace('.', '/') + ".class"

  private fun moduleClassBytes(): ByteArray {
    val root = File(BundlePreviewIds::class.java.protectionDomain.codeSource.location.toURI())
    val classFile = File(root, previewClassRel)
    check(classFile.isFile) { "expected a compiled class at $classFile" }
    return classFile.readBytes()
  }

  private fun newBundleTask(outDir: File): BundlePreviewTask {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val task = project.tasks.register("composePreviewBundle", BundlePreviewTask::class.java).get()
    val manifest =
      PreviewManifest(
        module = ":sample",
        variant = "debug",
        previews =
          listOf(
            PreviewInfo(
              id = "$previewClassFqn.sample",
              functionName = "sample",
              className = previewClassFqn,
            )
          ),
      )
    val previewsJson =
      File(outDir, "previews.json").apply {
        writeText(json.encodeToString(PreviewManifest.serializer(), manifest))
      }
    task.previewsJson.set(previewsJson)
    task.output.set(File(outDir, "bundle.png"))
    task.rendersDir.set(File(outDir, "renders").apply { mkdirs() })
    task.modulePath.set(":sample")
    task.producedBy.set("test")
    task.backend.set("android")
    task.previewIds.set(emptyList())
    return task
  }

  @Test
  fun `packs module classes from scoped PROJECT class dirs`() {
    val outDir = tmp.newFolder("out-dirs")
    val scoped = tmp.newFolder("scoped-dir")
    File(scoped, previewClassRel).apply {
      parentFile.mkdirs()
      writeBytes(moduleClassBytes())
    }

    val task = newBundleTask(outDir)
    // moduleClassDirs intentionally left empty — the scoped PROJECT dir is the only carrier.
    task.projectClassDirs.add(task.project.layout.projectDirectory.dir(scoped.absolutePath))
    task.pack()

    assertThat(appJarEntries(task.output.get().asFile)).contains(previewClassRel)
  }

  @Test
  fun `packs module classes from scoped PROJECT class jars`() {
    val outDir = tmp.newFolder("out-jars")
    val jar = File(tmp.newFolder("scoped-jar"), "module-classes.jar")
    ZipOutputStream(jar.outputStream().buffered()).use { zip ->
      zip.putNextEntry(ZipEntry(previewClassRel))
      zip.write(moduleClassBytes())
      zip.closeEntry()
    }

    val task = newBundleTask(outDir)
    task.projectClassJars.add(task.project.layout.projectDirectory.file(jar.absolutePath))
    task.pack()

    assertThat(appJarEntries(task.output.get().asFile)).contains(previewClassRel)
  }

  @Test
  fun `class is absent when neither moduleClassDirs nor the scoped artifact carry it`() {
    val outDir = tmp.newFolder("out-empty")
    val task = newBundleTask(outDir)
    task.pack()

    // Control: with nothing wired the bundle is still well-formed but cannot contain the class —
    // proving the two tests above pass because of the scoped wiring, not some ambient classpath.
    assertThat(appJarEntries(task.output.get().asFile)).doesNotContain(previewClassRel)
  }

  /** Entry names inside `classes/app.jar`, read out of the PNG+ZIP polyglot bundle. */
  private fun appJarEntries(bundle: File): Set<String> {
    val zipBytes = extractZipBytes(bundle.readBytes())
    val appJar = readZipEntry(zipBytes, "classes/app.jar") ?: return emptySet()
    val names = mutableSetOf<String>()
    ZipInputStream(ByteArrayInputStream(appJar)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        names += entry.name
        zin.closeEntry()
      }
    }
    return names
  }

  private fun readZipEntry(zipBytes: ByteArray, name: String): ByteArray? {
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        if (entry.name == name) {
          val out = zin.readBytes()
          zin.closeEntry()
          return out
        }
        zin.closeEntry()
      }
    }
    return null
  }

  /** Strip the leading PNG (the polyglot cover) so the trailing ZIP can be read. */
  private fun extractZipBytes(bytes: ByteArray): ByteArray {
    if (bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) return bytes
    var offset = 8 // PNG signature
    while (offset < bytes.size) {
      val length =
        ((bytes[offset].toInt() and 0xff) shl 24) or
          ((bytes[offset + 1].toInt() and 0xff) shl 16) or
          ((bytes[offset + 2].toInt() and 0xff) shl 8) or
          (bytes[offset + 3].toInt() and 0xff)
      val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
      offset += 4 + 4 + length + 4
      if (type == "IEND") return bytes.copyOfRange(offset, bytes.size)
    }
    error("PNG IEND not found")
  }
}
