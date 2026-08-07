package ee.schimke.composeai.cli

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `bundle merge` unions the per-preview artifacts of bundles packed from the same module + commit
 * but with disjoint render selections — the merge step of a sharded CI render. Pure zip surgery, so
 * [mergeShardBundles] is exercised directly (no Gradle, no daemon).
 *
 * The load-bearing difference from `bundle repack` is asserted here: a shard's preview is ADDED
 * (the base has no slot for it, because the base's render excluded it) and its `.semantics.json`
 * sidecar comes with it. Repack does neither, which is why the sharded pipeline cannot be built on
 * it — the design-catalog completeness gate fails a preview that has pixels but no semantics.
 */
class BundleMergeTest {

  private fun tempDir(prefix: String): File =
    Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

  private fun png(): ByteArray {
    val baos = ByteArrayOutputStream()
    ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", baos)
    return baos.toByteArray()
  }

  /** A minimal PNG+ZIP polyglot: the [cover] PNG followed by a zip of [entries]. */
  private fun polyglot(cover: ByteArray, entries: Map<String, ByteArray>): File {
    val zip = ByteArrayOutputStream()
    ZipOutputStream(zip).use { z ->
      for ((name, bytes) in entries) {
        z.putNextEntry(ZipEntry(name))
        z.write(bytes)
        z.closeEntry()
      }
    }
    return Files.createTempFile("bundle", ".png").toFile().also {
      it.deleteOnExit()
      it.writeBytes(cover + zip.toByteArray())
    }
  }

  private fun entryOf(bundle: File, name: String): ByteArray? {
    ZipInputStream(ByteArrayInputStream(BundleReader.extractZipBytes(bundle))).use { z ->
      while (true) {
        val e = z.nextEntry ?: break
        if (e.name == name) return z.readBytes()
        z.closeEntry()
      }
    }
    return null
  }

  /**
   * A shard as `bundle pack --exclude-preview-id` actually emits one: complete manifests (discovery
   * is unfiltered, so every shard lists every preview), the shared re-render classpath, and baked
   * artifacts for [baked] only.
   */
  private fun shard(baked: Map<String, String>, classpath: String = "CLASSES"): File =
    polyglot(
      png(),
      buildMap {
        put("bundle.json", """{"schemaVersion":8,"previewIds":["a","b","c"]}""".toByteArray())
        put("previews.json", """{"previews":[{"id":"a"},{"id":"b"},{"id":"c"}]}""".toByteArray())
        put("classes/app.jar", classpath.toByteArray())
        for ((id, pixels) in baked) {
          put("previews/$id.png", pixels.toByteArray())
          put("previews/$id.semantics.json", """{"id":"$id"}""".toByteArray())
          put("previews/$id.figma.svg", "<svg id='$id'/>".toByteArray())
        }
      },
    )

  @Test
  fun `adds a shard's previews and their semantics sidecars to the base`() {
    val base = shard(mapOf("a" to "PIXELS-A"))
    val second = shard(mapOf("b" to "PIXELS-B"))
    val out = File(tempDir("out"), "merged.png")

    val outcome = mergeShardBundles(base, listOf(second), out)

    assertEquals(1, outcome.previews, "one preview came from the shard")
    assertEquals(emptyList(), outcome.overlapping)
    // The base keeps its own render…
    assertContentEquals("PIXELS-A".toByteArray(), entryOf(out, "previews/a.png"))
    // …and the shard's preview is ADDED, slot and all — this is what repack cannot do.
    assertContentEquals("PIXELS-B".toByteArray(), entryOf(out, "previews/b.png"))
    // The sidecar the completeness gate checks travels with it.
    assertContentEquals("""{"id":"b"}""".toByteArray(), entryOf(out, "previews/b.semantics.json"))
    assertContentEquals("<svg id='b'/>".toByteArray(), entryOf(out, "previews/b.figma.svg"))
  }

  @Test
  fun `takes manifests and the re-render classpath from the base, never from a shard`() {
    // Every shard renders the same module at the same commit, so its classpath is byte-identical by
    // construction — but the base's is the one that must survive, or `publish-live-bundle` would
    // depend on which shard happened to be merged last.
    val base = shard(mapOf("a" to "PIXELS-A"), classpath = "BASE-CLASSES")
    val second = shard(mapOf("b" to "PIXELS-B"), classpath = "SHARD-CLASSES")
    val out = File(tempDir("out"), "merged.png")

    mergeShardBundles(base, listOf(second), out)

    assertContentEquals("BASE-CLASSES".toByteArray(), entryOf(out, "classes/app.jar"))
    assertContentEquals(
      """{"schemaVersion":8,"previewIds":["a","b","c"]}""".toByteArray(),
      entryOf(out, "bundle.json"),
    )
    assertContentEquals(
      """{"previews":[{"id":"a"},{"id":"b"},{"id":"c"}]}""".toByteArray(),
      entryOf(out, "previews.json"),
    )
  }

  @Test
  fun `merges every shard, and a preview no shard rendered simply stays unbaked`() {
    // `c` is deferred (modePriority) — excluded in every shard. It stays listed in previews.json
    // and carries no PNG, exactly as an unsharded deferred render leaves it.
    val base = shard(mapOf("a" to "PIXELS-A"))
    val out = File(tempDir("out"), "merged.png")

    val outcome =
      mergeShardBundles(base, listOf(shard(mapOf("b" to "PIXELS-B")), shard(emptyMap())), out)

    assertEquals(1, outcome.previews)
    assertContentEquals("PIXELS-B".toByteArray(), entryOf(out, "previews/b.png"))
    assertNull(entryOf(out, "previews/c.png"), "a deferred preview stays unbaked")
    assertNull(entryOf(out, "previews/c.semantics.json"))
  }

  @Test
  fun `reports previews more than one shard baked, and keeps the base's copy`() {
    val base = shard(mapOf("a" to "BASE-A"))
    val overlapping = shard(mapOf("a" to "SHARD-A", "b" to "PIXELS-B"))
    val out = File(tempDir("out"), "merged.png")

    val outcome = mergeShardBundles(base, listOf(overlapping), out)

    assertEquals(listOf("a"), outcome.overlapping, "a non-disjoint partition is reported")
    assertContentEquals("BASE-A".toByteArray(), entryOf(out, "previews/a.png"))
    assertEquals(1, outcome.previews, "only b was actually contributed")
  }

  @Test
  fun `an earlier shard wins over a later one`() {
    val base = shard(mapOf("a" to "PIXELS-A"))
    val first = shard(mapOf("b" to "FIRST-B"))
    val later = shard(mapOf("b" to "LATER-B"))
    val out = File(tempDir("out"), "merged.png")

    val outcome = mergeShardBundles(base, listOf(first, later), out)

    assertContentEquals("FIRST-B".toByteArray(), entryOf(out, "previews/b.png"))
    assertEquals(listOf("b"), outcome.overlapping)
  }

  @Test
  fun `carries nested figma-raster crops, ir documents and extension reports`() {
    val base = polyglot(png(), mapOf("bundle.json" to "{}".toByteArray()))
    val second =
      polyglot(
        png(),
        mapOf(
          "bundle.json" to """{"other":true}""".toByteArray(),
          "previews/b.png" to "PIXELS-B".toByteArray(),
          "previews/b.figma-raster/0.png" to "CROP".toByteArray(),
          "ir/b.rc" to "RC".toByteArray(),
          "extensions/b.json" to "REPORT".toByteArray(),
          "libs/dep.jar" to "DEP".toByteArray(),
        ),
      )
    val out = File(tempDir("out"), "merged.png")

    val outcome = mergeShardBundles(base, listOf(second), out)

    assertEquals(1, outcome.previews, "the nested crop is not counted as a preview")
    assertEquals(4, outcome.entries, "png + crop + ir + extension report")
    assertContentEquals("CROP".toByteArray(), entryOf(out, "previews/b.figma-raster/0.png"))
    assertContentEquals("RC".toByteArray(), entryOf(out, "ir/b.rc"))
    assertContentEquals("REPORT".toByteArray(), entryOf(out, "extensions/b.json"))
    // Shared carriage is inherited from the base, not merged: the shard's libs/ is not copied in.
    assertNull(entryOf(out, "libs/dep.jar"))
    assertContentEquals("{}".toByteArray(), entryOf(out, "bundle.json"))
  }

  @Test
  fun `rejects an input that is not a bundle`() {
    val base = shard(mapOf("a" to "PIXELS-A"))
    val notABundle = Files.createTempFile("junk", ".png").toFile().also { it.writeText("nonsense") }
    assertFailsWith<IllegalArgumentException> {
      mergeShardBundles(base, listOf(notABundle), File(tempDir("out"), "merged.png"))
    }
  }

  @Test
  fun `merging no shards is a faithful copy`() {
    val base = shard(mapOf("a" to "PIXELS-A"))
    val out = File(tempDir("out"), "merged.png")

    val outcome = mergeShardBundles(base, emptyList(), out)

    assertEquals(0, outcome.previews)
    assertEquals(0, outcome.entries)
    assertContentEquals(base.readBytes(), out.readBytes())
  }

  @Test
  fun `positionals lists every operand and skips valued flags`() {
    val args = listOf("base.png", "shard1.png", "-o", "out.png", "--verbose", "shard2.png")
    assertEquals(listOf("base.png", "shard1.png", "shard2.png"), CliFlags.positionals(args))
    assertTrue("out.png" !in CliFlags.positionals(args), "-o's value is not an operand")
  }
}
