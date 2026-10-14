package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
