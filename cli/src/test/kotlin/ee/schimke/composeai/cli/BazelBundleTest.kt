package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.bundle.producer.bazelBundleAppJar
import ee.schimke.composeai.bundle.producer.bazelBundleSha
import ee.schimke.composeai.bundle.producer.bazelMavenCoordinates
import ee.schimke.composeai.bundle.producer.matchBazelMaven
import ee.schimke.composeai.discovery.Capture
import ee.schimke.composeai.discovery.PreviewInfo
import ee.schimke.composeai.discovery.PreviewManifest
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BazelBundleTest {
  private fun jar(file: File, entries: Map<String, String>): File = file.apply {
    ZipOutputStream(outputStream()).use { zip ->
      entries.forEach { (name, text) ->
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray())
        zip.closeEntry()
      }
    }
  }

  @Test
  fun `transformed jars retain published coordinates and ambiguous groups need evidence`() {
    val root = Files.createTempDirectory("bundle-coordinates").toFile()
    try {
      val library = jar(root.resolve("processed_ui.jar"), mapOf("message" to "original"))
      val sha = bazelBundleSha(library)
      val lock =
        root.resolve("maven_install.json").apply {
          writeText(
            """{"artifacts":{"org.jetbrains.compose:ui":{"version":"1.0","shasums":{"jar":"$sha"}}}}"""
          )
        }
      assertEquals(
        "ui",
        matchBazelMaven(library, bazelMavenCoordinates(listOf(lock)))!!.coordinate.artifact,
      )
      jar(library, mapOf("message" to "transformed"))
      assertEquals(
        sha,
        matchBazelMaven(library, bazelMavenCoordinates(listOf(lock)))!!.coordinate.sha256,
      )
      val other =
        root.resolve("other.json").apply {
          writeText("""{"artifacts":{"other:ui":{"version":"1.0","shasums":{"jar":"$sha"}}}}""")
        }
      assertEquals(null, matchBazelMaven(library, bazelMavenCoordinates(listOf(lock, other))))
      other.writeText("{}")
      assertFailsWith<IllegalStateException> { bazelMavenCoordinates(listOf(other)) }
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `module resources survive and conflicting module entries fail`() {
    val root = Files.createTempDirectory("bundle-resources").toFile()
    try {
      val one =
        jar(root.resolve("one.jar"), mapOf("A.class" to "class", "data/message.txt" to "resource"))
      val entries = mutableMapOf<String, String>()
      ZipInputStream(ByteArrayInputStream(bazelBundleAppJar(listOf(one)))).use { zip ->
        while (true) {
          val entry = zip.nextEntry ?: break
          entries[entry.name] = zip.readBytes().decodeToString()
        }
      }
      assertEquals("resource", entries["data/message.txt"])
      val two = jar(root.resolve("two.jar"), mapOf("A.class" to "different"))
      assertFailsWith<IllegalArgumentException> { bazelBundleAppJar(listOf(one, two)) }
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `polyglot carries baked PNG own classes coordinates and runtime-only resource jar`() {
    val root = Files.createTempDirectory("bazel-polyglot").toFile()
    try {
      val app =
        jar(root.resolve("app.jar"), mapOf("App.class" to "class", "app.txt" to "own resource"))
      val published = jar(root.resolve("processed_ui.jar"), mapOf("Ui.class" to "dependency"))
      val runtime = jar(root.resolve("runtime.jar"), mapOf("runtime-message.txt" to "runtime-only"))
      val sha = bazelBundleSha(published)
      val lock =
        root.resolve("maven_install.json").apply {
          writeText(
            """{"artifacts":{"org.jetbrains.compose:ui":{"version":"1.0","shasums":{"jar":"$sha"}}}}"""
          )
        }
      val id = "example.App.Greeting"
      val manifest =
        PreviewManifest(
          "//:app",
          "desktop",
          listOf(
            PreviewInfo(
              id,
              "Greeting",
              "example.App",
              captures = listOf(Capture(renderOutput = "greeting.png")),
            )
          ),
        )
      val manifestFile =
        root.resolve("previews.json").apply { writeText(bazelJson.encodeToString(manifest)) }
      val png =
        Base64.getDecoder()
          .decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg=="
          )
      root.resolve("greeting.png").writeBytes(png)
      val metadata =
        BazelArtifacts(
          "//:app",
          "desktop",
          listOf(app.path),
          listOf(app.path, published.path, runtime.path),
        )
      val prepared = PreparedBazelPreviews(manifest, manifestFile)
      val bundle = root.resolve("bundle.png")
      // Preparation works with no image and never picks up stale captures from an earlier run.
      writeBazelBundle(
        metadata.resolve(root),
        prepared,
        setOf(id),
        listOf(lock),
        bundle,
        includeRenders = false,
      )
      val unrendered = bundle.readBytes()
      ZipInputStream(ByteArrayInputStream(BundleReader.extractZipBytes(bundle))).use { zip ->
        val names = generateSequence { zip.nextEntry }.map { it.name }.toList()
        assertTrue("previews/$id.png" !in names)
      }
      writeBazelBundle(
        metadata.resolve(root),
        prepared,
        setOf(id),
        listOf(lock),
        bundle,
        includeRenders = false,
      )
      assertTrue(
        unrendered.contentEquals(bundle.readBytes()),
        "Unchanged preparation must be deterministic",
      )
      val captures = root.resolve("captures").apply { mkdirs() }
      captures.resolve("$id.png").writeBytes(png)
      val baked = root.resolve("baked.png")
      ee.schimke.composeai.bundle.producer.bakePreparedBundle(bundle, captures, baked)
      assertEquals(
        BundleReader.readMetadata(bundle).manifest,
        BundleReader.readMetadata(baked).manifest,
      )
      assertTrue(baked.readBytes().take(png.size).toByteArray().contentEquals(png))
      captures.resolve("$id.png").delete()
      assertFailsWith<IllegalArgumentException> {
        ee.schimke.composeai.bundle.producer.bakePreparedBundle(bundle, captures, baked)
      }
      writeBazelBundle(metadata.resolve(root), prepared, setOf(id), listOf(lock), bundle)
      val read = BundleReader.readMetadata(bundle).manifest
      assertEquals("bazel", read.producer)
      assertEquals("mixed", read.resolution)
      assertEquals(
        sha,
        read.classpath.filterIsInstance<BundleReader.ClasspathEntry.Maven>().single().sha256,
      )
      assertTrue(bundle.readBytes().take(png.size).toByteArray().contentEquals(png))
      val entries = mutableMapOf<String, ByteArray>()
      ZipInputStream(ByteArrayInputStream(BundleReader.extractZipBytes(bundle))).use { zip ->
        while (true) {
          val entry = zip.nextEntry ?: break
          entries[entry.name] = zip.readBytes()
        }
      }
      assertTrue(entries.getValue("previews/$id.png").contentEquals(png))
      assertTrue(entries.containsKey("classes/app.jar"))
      assertTrue(
        entries
          .getValue(
            read.classpath
              .filterIsInstance<BundleReader.ClasspathEntry.Embedded>()
              .single()
              .inlinedAs
          )
          .contentEquals(runtime.readBytes())
      )
      writeBazelBundle(
        metadata.resolve(root),
        prepared,
        setOf(id),
        listOf(lock),
        bundle,
        embedDeps = true,
      )
      assertEquals("embedded", BundleReader.readMetadata(bundle).manifest.resolution)
      assertTrue(
        BundleReader.readMetadata(bundle).manifest.classpath.none {
          it is BundleReader.ClasspathEntry.Maven
        }
      )
    } finally {
      root.deleteRecursively()
    }
  }
}
