package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.bundle.coordinates.CoordinateResolver
import ee.schimke.composeai.bundle.producer.bazelBundleSha
import ee.schimke.composeai.bundle.producer.writeBazelBundle
import ee.schimke.composeai.discovery.Capture
import ee.schimke.composeai.discovery.PreviewInfo
import ee.schimke.composeai.discovery.PreviewManifest
import java.io.File
import java.nio.file.Files
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `bundle render --local-jars`: a Bazel render resolves the coordinates its prepared bundle records
 * from the very jars the producer identified, by content, whatever they are called and whatever a
 * lockfile pins. The shapes are the ones that failed offline: rules_kotlin's unversioned
 * `kotlin-stdlib.jar` (newer than the lockfile's pin, identified from its manifest) and
 * rules_jvm_external's stamped `processed_` jars (not the published bytes).
 */
class BundleLocalJarsTest {
  private fun jar(file: File, version: String?, vararg entries: Pair<String, String>): File =
    file.apply {
      parentFile.mkdirs()
      val manifest =
        Manifest().apply {
          mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
          version?.let { mainAttributes[Attributes.Name.IMPLEMENTATION_VERSION] = it }
        }
      JarOutputStream(outputStream(), manifest).use { zip ->
        entries.forEach { (name, text) ->
          zip.putNextEntry(ZipEntry(name))
          zip.write(text.toByteArray())
          zip.closeEntry()
        }
      }
    }

  private class Fixture(val root: File) {
    val app = File(root, "bazel-out/bin/app/libapp.jar")
    // rules_kotlin's toolchain stdlib: no version in its name or path.
    val stdlib = File(root, "external/rules_kotlin+/lib/kotlin-stdlib.jar")
    // jvm_import's stamped copy of a pinned jar: same coordinate, different bytes.
    val stamped = File(root, "bazel-out/bin/external/maven/processed_collection-jvm-1.4.4.jar")
    val bundle = File(root, "app.bundle.png")
    lateinit var manifest: BundleReader.Manifest
  }

  private fun fixture(): Fixture {
    val f = Fixture(Files.createTempDirectory("bundle-local-jars").toFile())
    jar(f.app, null, "com/example/AppKt.class" to "app")
    jar(f.stdlib, "2.1.21", "kotlin/Unit.class" to "stdlib 2.1.21")
    jar(f.stamped, null, "androidx/collection/ArraySet.class" to "collection")
    val published =
      jar(File(f.root, "published.jar"), null, "androidx/collection/ArraySet.class" to "published")
    val lock =
      File(f.root, "maven_install.json").apply {
        writeText(
          """{"artifacts":{
            |"org.jetbrains.kotlin:kotlin-stdlib":{"version":"1.8.22","shasums":{"jar":"${"0".repeat(64)}"}},
            |"androidx.collection:collection-jvm":{"version":"1.4.4","shasums":{"jar":"${bazelBundleSha(published)}"}}
            |},"version":"2"}"""
            .trimMargin()
        )
      }
    val preview =
      PreviewInfo(
        "com.example.AppKt.Greeting",
        "Greeting",
        "com.example.AppKt",
        captures = listOf(Capture(renderOutput = "greeting.png")),
      )
    f.manifest =
      writeBazelBundle(
        "//app",
        listOf(f.app),
        listOf(f.app, f.stdlib, f.stamped),
        PreviewManifest("//app", "desktop", listOf(preview)),
        null,
        setOf(preview.id),
        listOf(lock),
        f.bundle,
      )
    return f
  }

  @Test
  fun `recorded coordinates map to the exact jars the producer identified`() {
    val f = fixture()
    try {
      val coordinates =
        f.manifest.classpath.filterIsInstance<BundleReader.ClasspathEntry.Maven>().map { it.gav() }
      // What the producer decided, which a name-based guess at the lockfile cannot reproduce.
      assertEquals(
        listOf(
          "org.jetbrains.kotlin:kotlin-stdlib:2.1.21",
          "androidx.collection:collection-jvm:1.4.4",
        ),
        coordinates,
      )
      val local =
        bundleLocalJars(BundleReader.extractZipBytes(f.bundle), listOf(f.app, f.stdlib, f.stamped))
      assertEquals(
        mapOf(
          "org.jetbrains.kotlin:kotlin-stdlib:2.1.21" to f.stdlib,
          "androidx.collection:collection-jvm:1.4.4" to f.stamped,
        ),
        local,
      )
    } finally {
      f.root.deleteRecursively()
    }
  }

  @Test
  fun `local coordinates resolve offline without a repository lookup or hash warning`() {
    val f = fixture()
    try {
      val warnings = mutableListOf<String>()
      val coords = f.manifest.classpath.filterIsInstance<BundleReader.ClasspathEntry.Maven>()
      val local =
        bundleLocalJars(BundleReader.extractZipBytes(f.bundle), listOf(f.stdlib, f.stamped))
      val resolved =
        resolveBundleCoordinates(
          CoordinateResolver(
            repositoryRoots = listOf(File(f.root, "empty-m2")),
            warn = { warnings += it },
            networkEnabled = false,
            downloadCacheDir = File(f.root, "empty-cache"),
          ),
          coords,
          disabled = true,
          local = local,
        )
      assertEquals(listOf(f.stdlib, f.stamped), resolved)
      assertTrue(warnings.isEmpty(), warnings.toString())
    } finally {
      f.root.deleteRecursively()
    }
  }

  @Test
  fun `jars the bundle does not record, and bundles without records, map to nothing`() {
    val f = fixture()
    try {
      val unrelated = jar(File(f.root, "other.jar"), null, "Other.class" to "other")
      assertEquals(
        emptyMap(),
        bundleLocalJars(BundleReader.extractZipBytes(f.bundle), listOf(unrelated)),
      )
      assertEquals(emptyMap(), bundleLocalJars(BundleReader.extractZipBytes(f.bundle), emptyList()))
    } finally {
      f.root.deleteRecursively()
    }
  }

  @Test
  fun `the jar list is read relative to the working directory`() {
    val root = Files.createTempDirectory("local-jar-list").toFile()
    try {
      val list = File(root, "jars.txt").apply { writeText("a/one.jar\n\n/abs/two.jar\n") }
      assertEquals(
        listOf(File("a/one.jar").absoluteFile, File("/abs/two.jar")),
        readLocalJarList(list),
      )
    } finally {
      root.deleteRecursively()
    }
  }
}
