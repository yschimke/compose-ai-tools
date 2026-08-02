package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards [BundleDaemonCommand.composeDaemonClasspath] — the bundle-daemon fix for the IR-replay
 * classloader gap characterised in `:daemon:core`'s `IrReplayClassloaderTopologyTest`. The
 * parent-loaded replay host can only link the carried renderer/player libs if they're also on the
 * daemon launch `-cp`; this pins that they're appended for IR bundles and the classic path is left
 * alone.
 */
class BundleDaemonClasspathTest {

  private val sidecar =
    listOf("/lib/daemon.jar", "/lib/renderer.jar").joinToString(File.pathSeparator)
  private val carried =
    listOf(File("/work/libs/remote-player.jar"), File("/work/libs/protolayout-renderer.jar"))

  @Test
  fun `classic non-IR bundle leaves the daemon classpath untouched`() {
    assertEquals(
      sidecar,
      BundleDaemonCommand.composeDaemonClasspath(sidecar, carried, hasIr = false),
    )
  }

  @Test
  fun `IR bundle appends carried deps so the parent-loaded replay host links them`() {
    val cp = BundleDaemonCommand.composeDaemonClasspath(sidecar, carried, hasIr = true)
    val entries = cp.split(File.pathSeparator)

    // Sidecar entries stay first — the renderer's bundled Compose must remain authoritative.
    assertEquals(listOf("/lib/daemon.jar", "/lib/renderer.jar"), entries.take(2))
    // The carried player / tiles-renderer libs are present so the parent can resolve them.
    assertTrue(
      carried.all { it.absolutePath in entries },
      "carried deps must be on the parent -cp, got $entries",
    )
    // ...and appended *after* the sidecar, never shadowing it.
    assertTrue(
      entries.indexOf("/lib/renderer.jar") < entries.indexOf(carried.first().absolutePath),
      "carried deps must come after the sidecar jars",
    )
  }

  @Test
  fun `IR bundle with no carried deps is a no-op`() {
    assertEquals(
      sidecar,
      BundleDaemonCommand.composeDaemonClasspath(sidecar, emptyList(), hasIr = true),
    )
  }

  @Test
  fun `shared IR extraction materializes the document and bundle manifest`() {
    val root = Files.createTempDirectory("bundle-ir-extraction").toFile()
    val bundle = File(root, "bundle.png")
    val manifest = """{"schemaVersion":8,"intermediateRepresentations":[]}""".toByteArray()
    val document = byteArrayOf(0x52, 0x43, 0x01)
    val zipBytes =
      java.io
        .ByteArrayOutputStream()
        .also { bytes ->
          ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("bundle.json"))
            zip.write(manifest)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("ir/com.example.Remote.rc"))
            zip.write(document)
            zip.closeEntry()
          }
        }
        .toByteArray()
    val irDir = File(root, "ir").apply { mkdirs() }
    val extractedManifest = File(root, "bundle.json")

    extractBundleIrArtifacts(zipBytes, irDir, extractedManifest, bundle)

    assertTrue(extractedManifest.readBytes().contentEquals(manifest))
    assertTrue(File(irDir, "com.example.Remote.rc").readBytes().contentEquals(document))
  }
}
