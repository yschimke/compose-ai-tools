package ee.schimke.composeai.viewer

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit coverage for [loadBundle] — exercises the bundle-on-disk happy path, the schema mirror's
 * `kind`-discriminated polymorphic deserialisation, and per-preview error reporting when a declared
 * preview class isn't in `classes/app.jar`. UI behaviour (`Window`, drop target, live composition)
 * is harder to cover without a display server and is exercised at the integration level via the
 * headless xvfb smoke shown in the PR description.
 */
class BundleLoaderTest {

  @get:Rule val tempDir: TemporaryFolder = TemporaryFolder()

  @Test
  fun `loadBundle parses a minimal png-zip polyglot bundle`() {
    val bundle = writeMinimalBundle(includeAppClass = false)

    val loaded = runBlocking { loadBundle(bundle.path.toPath()) }
    try {
      assertThat(loaded.bundleManifest.schemaVersion).isEqualTo(1)
      assertThat(loaded.bundleManifest.backend).isEqualTo("desktop")
      assertThat(loaded.bundleManifest.previewIds).containsExactly("test.PreviewsKt.MissingPreview")
      assertThat(loaded.bundleManifest.classpath).hasSize(2)
      assertThat(loaded.bundleManifest.classpath[0]).isInstanceOf(ClasspathEntry.Module::class.java)
      val maven = loaded.bundleManifest.classpath[1] as ClasspathEntry.Maven
      assertThat(maven.group).isEqualTo("androidx.compose.ui")
      assertThat(maven.type).isEqualTo("jar")

      assertThat(loaded.previewManifest.previews).hasSize(1)
      assertThat(loaded.coverPreview.info.id).isEqualTo("test.PreviewsKt.MissingPreview")
      // Class is absent in the bundle so resolution must surface an error rather than crash.
      assertThat(loaded.coverPreview.composableMethod).isNull()
      assertThat(loaded.coverPreview.errorMessage).contains("test.PreviewsKt")
    } finally {
      loaded.close()
    }
  }

  @Test
  fun `loadBundle rejects non-bundle files`() {
    val txt = tempDir.newFile("not-a-bundle.txt").apply { writeText("hello world") }

    val ex =
      runCatching { runBlocking { loadBundle(txt.path.toPath()) } }.exceptionOrNull()
        ?: error("expected loadBundle to throw on non-bundle input")
    assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun `loadBundle puts embedded libs jars on the bundle classloader`() {
    // A v3 embedded bundle: an extra jar under libs/ carrying a class that is NOT on the viewer's
    // own classpath. The loader must extract it and add it to the child URLClassLoader so the
    // preview's third-party deps resolve (the whole point of --embed-deps / resolution=embedded).
    //
    // The preview's owner class `test.PreviewsKt` is itself placed in classes/app.jar so that
    // `loadBundle` resolves it via the bundle's URLClassLoader (rather than the placeholder it
    // falls
    // back to when the class is missing) — giving us a handle on the *actual* bundle loader to
    // prove
    // the embedded jar landed on the same classpath.
    val embeddedClassFqn = "com.example.embedded.Widget"
    val bundle =
      writeMinimalBundle(
        includeAppClass = true,
        resolution = "embedded",
        extraClasspath = listOf(ClasspathEntry.Embedded(inlinedAs = "libs/embedded.jar")),
        libs = mapOf("libs/embedded.jar" to singleClassJar(embeddedClassFqn)),
      )

    val loaded = runBlocking { loadBundle(bundle.path.toPath()) }
    try {
      assertThat(loaded.bundleManifest.resolution).isEqualTo("embedded")
      // `test.PreviewsKt` resolved → ownerClass is the real class loaded by the bundle's loader.
      val bundleLoader = loaded.coverPreview.ownerClass.classLoader!!
      assertThat(loaded.coverPreview.ownerClass.name).isEqualTo("test.PreviewsKt")
      // The embedded class is absent from the viewer's own classloader…
      assertThat(runCatching { Class.forName(embeddedClassFqn) }.isFailure).isTrue()
      // …but loads via the bundle's loader because libs/embedded.jar is on its classpath.
      val resolved = Class.forName(embeddedClassFqn, false, bundleLoader)
      assertThat(resolved.name).isEqualTo(embeddedClassFqn)
    } finally {
      loaded.close()
    }
  }

  /** A jar carrying a single trivial public class [fqn] (see [minimalClassBytes]). */
  private fun singleClassJar(fqn: String): ByteArray {
    val classBytes = minimalClassBytes(fqn)
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zip ->
      zip.putNextEntry(ZipEntry(fqn.replace('.', '/') + ".class"))
      zip.write(classBytes)
      zip.closeEntry()
    }
    return baos.toByteArray()
  }

  /**
   * Emit a minimal but verifiable `public class <fqn> extends Object` with no fields/methods/
   * interfaces. Loadable via `Class.forName(fqn, initialize = false, loader)` — that path needs no
   * constructor and doesn't run `<clinit>`, so an empty class body is enough to prove the embedded
   * jar made it onto the classloader. Avoids pulling ASM onto the viewer's test classpath.
   */
  private fun minimalClassBytes(fqn: String): ByteArray {
    val internalName = fqn.replace('.', '/')
    val baos = ByteArrayOutputStream()
    java.io.DataOutputStream(baos).use { out ->
      out.writeInt(-0x35014542) // 0xCAFEBABE magic
      out.writeShort(0) // minor version
      out.writeShort(52) // major version = Java 8
      // Constant pool: count = 5 (entries 1..4).
      out.writeShort(5)
      out.writeByte(1) // #1 Utf8
      out.writeUTF(internalName)
      out.writeByte(7) // #2 Class → #1
      out.writeShort(1)
      out.writeByte(1) // #3 Utf8
      out.writeUTF("java/lang/Object")
      out.writeByte(7) // #4 Class → #3
      out.writeShort(3)
      out.writeShort(0x0021) // access flags: ACC_PUBLIC | ACC_SUPER
      out.writeShort(2) // this_class = #2
      out.writeShort(4) // super_class = #4
      out.writeShort(0) // interfaces_count
      out.writeShort(0) // fields_count
      out.writeShort(0) // methods_count
      out.writeShort(0) // attributes_count
    }
    return baos.toByteArray()
  }

  /**
   * Build a tiny but well-formed PNG+ZIP polyglot. The PNG is just a sanity 1×1 cover; the zip
   * carries the manifests + an empty `classes/app.jar` so resolution exercises the
   * "class-not-found" branch deterministically.
   */
  private fun writeMinimalBundle(
    includeAppClass: Boolean = false,
    resolution: String = "coordinates",
    extraClasspath: List<ClasspathEntry> = emptyList(),
    libs: Map<String, ByteArray> = emptyMap(),
  ): File {
    val zipBytes = ByteArrayOutputStream()
    ZipOutputStream(zipBytes).use { zip ->
      val bundleJson =
        Json.encodeToString(
          BundleManifest.serializer(),
          BundleManifest(
            schemaVersion = if (resolution == "coordinates") 1 else 3,
            backend = "desktop",
            previewIds = listOf("test.PreviewsKt.MissingPreview"),
            coverPreviewId = "test.PreviewsKt.MissingPreview",
            classpath =
              listOf(
                ClasspathEntry.Module(path = "classes/app.jar"),
                ClasspathEntry.Maven(
                  group = "androidx.compose.ui",
                  artifact = "ui-desktop",
                  version = "1.10.3",
                  type = "jar",
                ),
              ) + extraClasspath,
            modulePath = ":test",
            producedBy = "test-suite",
            resolution = resolution,
          ),
        )
      zip.putNextEntry(ZipEntry("bundle.json"))
      zip.write(bundleJson.toByteArray(Charsets.UTF_8))
      zip.closeEntry()

      val previewsJson =
        Json.encodeToString(
          PreviewManifest.serializer(),
          PreviewManifest(
            module = "test",
            variant = "desktop",
            previews =
              listOf(
                PreviewInfo(
                  id = "test.PreviewsKt.MissingPreview",
                  functionName = "MissingPreview",
                  className = "test.PreviewsKt",
                )
              ),
          ),
        )
      zip.putNextEntry(ZipEntry("previews.json"))
      zip.write(previewsJson.toByteArray(Charsets.UTF_8))
      zip.closeEntry()

      // classes/app.jar: empty by default (exercises the class-not-found branch). When
      // [includeAppClass] is set, carry the preview's owner class so resolution succeeds and the
      // owner class is loaded by the bundle's own URLClassLoader.
      val appJarBytes = ByteArrayOutputStream()
      ZipOutputStream(appJarBytes).use { appJar ->
        if (includeAppClass) {
          appJar.putNextEntry(ZipEntry("test/PreviewsKt.class"))
          appJar.write(minimalClassBytes("test.PreviewsKt"))
          appJar.closeEntry()
        }
      }
      zip.putNextEntry(ZipEntry("classes/app.jar"))
      zip.write(appJarBytes.toByteArray())
      zip.closeEntry()

      for ((path, bytes) in libs) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
      }
    }

    // Synthesise a 1×1 PNG header so the polyglot is recognised by `extractZipBytes`. Same byte
    // sequence the plugin's stub gray cover would produce — see PreviewBundleFormat.kt.
    val pngHeader = stubPng1x1()
    val out = tempDir.newFile("bundle.png")
    out.outputStream().use { sink ->
      sink.write(pngHeader)
      sink.write(zipBytes.toByteArray())
    }
    return out
  }

  /**
   * Minimal valid 1×1 PNG. Hand-rolled (CRCs included) so the test doesn't need `BufferedImage` /
   * `ImageIO` plumbing — the file just needs the PNG signature + a valid IHDR chain so the polyglot
   * extractor can locate the IEND boundary.
   */
  private fun stubPng1x1(): ByteArray {
    // Pre-computed by running `BufferedImage(1,1).also { it.setRGB(0,0,0x808080) }` through
    // `ImageIO.write(_, "png", baos)` and copying the bytes. Stable across Java releases.
    return byteArrayOf(
      -119,
      80,
      78,
      71,
      13,
      10,
      26,
      10,
      0,
      0,
      0,
      13,
      73,
      72,
      68,
      82,
      0,
      0,
      0,
      1,
      0,
      0,
      0,
      1,
      8,
      2,
      0,
      0,
      0,
      -112,
      119,
      83,
      -34,
      0,
      0,
      0,
      12,
      73,
      68,
      65,
      84,
      8,
      -41,
      99,
      -8,
      -65,
      -65,
      63,
      0,
      5,
      -2,
      2,
      -2,
      -86,
      -54,
      -3,
      -103,
      0,
      0,
      0,
      0,
      73,
      69,
      78,
      68,
      -82,
      66,
      96,
      -126,
    )
  }
}
