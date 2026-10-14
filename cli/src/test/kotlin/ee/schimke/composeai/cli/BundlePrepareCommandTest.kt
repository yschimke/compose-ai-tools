package ee.schimke.composeai.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `bundle prepare` lays a bundle's daemon workspace out where a Gradle module keeps its own, so a
 * descriptor scan for `build/compose-previews/daemon-launch.json` finds it. The materialization is
 * [ee.schimke.composeai.cli.serve.ServeBundleDaemon]'s, covered by its own tests; a fake stands in.
 */
class BundlePrepareCommandTest {
  private fun temp(block: (File) -> Unit) {
    val dir = Files.createTempDirectory("bundle-prepare").toFile()
    try {
      block(dir)
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `the descriptor lands in the workspace's build compose-previews`() = temp { dir ->
    val bundle = File(dir, "catalog.png").apply { writeText("") }
    val workspace = File(dir, "ws")
    var seen: Triple<File, File, String>? = null
    val command =
      BundlePrepareCommand(emptyList()) { b, dest, system ->
        seen = Triple(b, dest, system)
        File(dest, "daemon-launch.json").apply { writeText("{}") }
      }

    val descriptor = command.prepare(bundle, workspace)

    assertEquals(
      File(workspace.absoluteFile, "build/compose-previews/daemon-launch.json"),
      descriptor,
    )
    assertEquals(Triple(bundle, descriptor!!.parentFile, "catalog"), seen)
  }

  @Test
  fun `a repacked bundle replaces the previous extraction, and a failure reports null`() =
    temp { dir ->
      val bundle = File(dir, "catalog.png").apply { writeText("") }
      val workspace = File(dir, "ws")
      val stale =
        File(workspace, "build/compose-previews/classes/Old.class").apply {
          parentFile.mkdirs()
          writeText("")
        }
      val marker = File(workspace, "compose-preview-bundle.txt").apply { writeText("x") }

      assertNull(BundlePrepareCommand(emptyList()) { _, _, _ -> null }.prepare(bundle, workspace))
      assertFalse(stale.exists(), "the previous bundle's classes must not survive")
      // Only the output dir is replaced; whatever else the caller keeps in the workspace stays.
      assertTrue(marker.isFile)
    }

  /** A zip-only bundle whose `classes/app.jar` was published to a pool instead of carried. */
  private fun thinBundle(dir: File, appJar: ByteArray): File {
    val sha =
      MessageDigest.getInstance("SHA-256").digest(appJar).joinToString("") { "%02x".format(it) }
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
      fun put(name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.encodeToByteArray())
        zip.closeEntry()
      }
      put(
        "bundle.json",
        """{"schemaVersion":8,"backend":"desktop","previewIds":["A"],"coverPreviewId":"A",""" +
          """"classpath":[{"kind":"module","path":"classes/app.jar"}],"modulePath":":app",""" +
          """"producedBy":"test","externalClasspath":[{"path":"classes/app.jar",""" +
          """"sha256":"$sha","size":${appJar.size}}]}""",
      )
      put("previews.json", """{"previews":[{"id":"A"}]}""")
    }
    return File(dir, "thin.png").apply { writeBytes(bytes.toByteArray()) }
  }

  @Test
  fun `an externalized bundle is hydrated from the pool, and refused without one`() = temp { dir ->
    val appJar = "APP-JAR".encodeToByteArray()
    val bundle = thinBundle(dir, appJar)
    var materialized: File? = null
    val command =
      BundlePrepareCommand(emptyList()) { b, dest, _ ->
        materialized = b
        File(dest, "daemon-launch.json").apply { writeText("{}") }
      }

    val refused =
      assertFailsWith<IllegalStateException> { command.prepare(bundle, File(dir, "ws")) }
    assertTrue(refused.message!!.contains("--res"))

    val pool = File(dir, "pool").apply { mkdirs() }
    val sha =
      MessageDigest.getInstance("SHA-256").digest(appJar).joinToString("") { "%02x".format(it) }
    File(pool, sha).writeBytes(appJar)
    assertNotNull(command.prepare(bundle, File(dir, "ws"), pool))

    // The daemon is prepared from the self-contained copy, which carries the jar again.
    val hydrated = assertNotNull(materialized)
    assertEquals(BundlePrepareCommand.HYDRATED_BUNDLE, hydrated.name)
    ZipInputStream(hydrated.inputStream()).use { zip ->
      val names = generateSequence { zip.nextEntry }.map { it.name }.toList()
      assertTrue("classes/app.jar" in names, "hydrated entries: $names")
    }
  }
}
