package ee.schimke.composeai.bundle.producer

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.discovery.Capture
import ee.schimke.composeai.discovery.PreviewInfo
import ee.schimke.composeai.discovery.PreviewManifest
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The producer's half of an Android `compose_preview` target: a schema-2 artifacts file's `android`
 * block becomes a `backend="android"` bundle, and baking finds the Android renderer's captures.
 */
class AndroidArtifactsTest {
  private fun jar(file: File, vararg entries: Pair<String, String>): File = file.apply {
    parentFile.mkdirs()
    ZipOutputStream(outputStream()).use { zip ->
      entries.forEach { (name, value) ->
        zip.putNextEntry(ZipEntry(name))
        zip.write(value.toByteArray())
        zip.closeEntry()
      }
    }
  }

  private fun artifacts(android: String?): kotlinx.serialization.json.JsonObject =
    Json.parseToJsonElement(
        """{"schemaVersion":2,"target":"//app:ui","configuration":"bazel-out/k8-fastbuild/bin",
          |"projectJars":[],"runtimeJars":[],"sourceFiles":[],"dataFiles":[]
          |${android?.let { ""","android":$it""" } ?: ""}}"""
          .trimMargin()
      )
      .jsonObject

  @Test
  fun `the android block names the linked resources`() {
    val root = Files.createTempDirectory("android-artifacts").toFile()
    try {
      val apk = root.resolve("resources.ap_").apply { writeText("apk") }
      val manifest = root.resolve("AndroidManifest.xml").apply { writeText("<manifest/>") }
      val rJar = jar(root.resolve("r-final.jar"), "com/example/R.class" to "r")
      val resources =
        androidResources(
          artifacts(
            """{"packageName":"com.example.ui","resourceApk":"${apk.path}",
              |"manifest":"${manifest.path}","rJar":"${rJar.path}","compileSdk":36}"""
              .trimMargin()
          )
        )!!
      assertEquals("com.example.ui", resources.packageName)
      assertEquals(apk, resources.resourceApk)
      assertEquals(manifest, resources.manifest)
      assertEquals(rJar, resources.rJar)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `schema 1 artifacts have no android resources`() {
    assertNull(androidResources(artifacts(null)))
  }

  @Test
  fun `an unmaterialized resource apk is refused`() {
    val error =
      assertFailsWith<IllegalArgumentException> {
        androidResources(
          artifacts(
            """
            |{"packageName":"com.example.ui","resourceApk":"missing/resources.ap_",
            |"manifest":"missing/AndroidManifest.xml"}
            """
              .trimMargin()
          )
        )
      }
    assertTrue("android.resourceApk" in error.message.orEmpty(), error.message)
  }

  @Test
  fun `an unknown schema is refused`() {
    val root = Files.createTempDirectory("android-artifacts").toFile()
    try {
      val file = root.resolve("artifacts.json").apply { writeText("""{"schemaVersion":3}""") }
      val error =
        assertFailsWith<IllegalArgumentException> {
          BundleProducerCli.main(
            arrayOf("--artifacts", file.path, "--output", root.resolve("b.png").path)
          )
        }
      assertTrue("schema 3" in error.message.orEmpty(), error.message)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `the android block must be present exactly for schema 2`() {
    val root = Files.createTempDirectory("android-artifacts").toFile()
    try {
      for ((metadata, expected) in
        listOf(
          """{"schemaVersion":2}""" to "schemaVersion 2 artifacts need an android block",
          """{"schemaVersion":2,"android":null}""" to
            "schemaVersion 2 artifacts need an android block",
          """{"schemaVersion":1,"android":{}}""" to
            "schemaVersion 1 artifacts cannot carry an android block",
        )) {
        val file = root.resolve("artifacts.json").apply { writeText(metadata) }
        val error =
          assertFailsWith<IllegalArgumentException> {
            BundleProducerCli.main(
              arrayOf("--artifacts", file.path, "--output", root.resolve("b.png").path)
            )
          }
        assertTrue(expected in error.message.orEmpty(), error.message)
      }
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `a null android block on schema 1 counts as absent`() {
    val root = Files.createTempDirectory("android-artifacts").toFile()
    try {
      val file =
        root.resolve("artifacts.json").apply { writeText("""{"schemaVersion":1,"android":null}""") }
      val error =
        assertFailsWith<Exception> {
          BundleProducerCli.main(
            arrayOf("--artifacts", file.path, "--output", root.resolve("b.png").path)
          )
        }
      assertTrue("android block" !in error.message.orEmpty(), error.message)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `an android bundle carries the resources and bakes the renderer's capture names`() {
    val root = Files.createTempDirectory("android-bundle").toFile()
    try {
      val app = jar(root.resolve("app.jar"), "com/example/ui/GreetingKt.class" to "compiled")
      val rJar = jar(root.resolve("r-final.jar"), "com/example/ui/R.class" to "r")
      val apk = root.resolve("resources.ap_").apply { writeText("apk") }
      val manifest = root.resolve("AndroidManifest.xml").apply { writeText("<manifest/>") }
      val preview =
        PreviewInfo(
          "com.example.ui.GreetingKt.Greeting",
          "Greeting",
          "com.example.ui.GreetingKt",
          captures = listOf(Capture(renderOutput = "renders/Greeting.png")),
        )
      val bundle = root.resolve("bundle.png")
      val written =
        writeBazelBundle(
          "//app:ui",
          listOf(app),
          listOf(rJar, app),
          PreviewManifest("//app:ui", "android", listOf(preview)),
          null,
          setOf(preview.id),
          emptyList(),
          bundle,
          embedDeps = false,
          android = BazelAndroidResources("com.example.ui", apk, manifest, rJar),
        )
      assertEquals("android", written.backend)
      assertEquals("com.example.ui", written.androidResources?.applicationPackage)
      // The final-ID R classes travel as the android payload, not as a classpath entry.
      assertTrue(written.classpath.none { it is BundleReader.ClasspathEntry.Embedded })
      ZipFile(
          BundleReader.extractZipBytes(bundle).let { bytes ->
            root.resolve("bundle.zip").apply { writeBytes(bytes) }
          }
        )
        .use { zip ->
          assertTrue(zip.getEntry("android/resources.ap_") != null)
          assertTrue(zip.getEntry("android/r-classes.jar") != null)
        }

      // The Android renderer writes `Greeting.png` (the capture leaf), not the id-derived name.
      val renders = root.resolve("renders").apply { mkdirs() }
      val png =
        java.util.Base64.getDecoder()
          .decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg=="
          )
      renders.resolve("Greeting.png").writeBytes(png)
      val baked = root.resolve("baked.png")
      bakePreparedBundle(bundle, renders, baked)
      ZipFile(root.resolve("baked.zip").apply { writeBytes(BundleReader.extractZipBytes(baked)) })
        .use { zip ->
          val entry = zip.getEntry("previews/${preview.id}.png")
          assertContentEquals(png, zip.getInputStream(entry).readBytes())
        }
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `aar pins in a rules_jvm_external lockfile are known coordinates`() {
    val root = Files.createTempDirectory("aar-pins").toFile()
    try {
      val lock =
        root.resolve("maven_install.json").apply {
          writeText(
            """
            |{"artifacts":{
            |"androidx.activity:activity-compose:aar":{"shasums":{"jar":"${"a".repeat(64)}"},"version":"1.10.1"},
            |"org.jetbrains.kotlin:kotlin-stdlib":{"shasums":{"jar":"${"b".repeat(64)}"},"version":"2.0.0"},
            |"com.example:natives:jar:linux-x86_64":{"shasums":{"jar":"${"c".repeat(64)}"},"version":"1.0"},
            |"com.example:parent:pom":{"shasums":{"jar":null},"version":"1.0"}}}
            """
              .trimMargin()
          )
        }
      assertEquals(
        setOf(
          "androidx.activity:activity-compose:1.10.1:aar",
          "org.jetbrains.kotlin:kotlin-stdlib:2.0.0:jar",
        ),
        bazelMavenCoordinates(listOf(lock))
          .map { "${it.group}:${it.artifact}:${it.version}:${it.type}" }
          .toSet(),
      )
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `an android bundle references aar_import jars by coordinate`() {
    val root = Files.createTempDirectory("aar-bundle").toFile()
    try {
      val app = jar(root.resolve("app.jar"), "com/example/ui/GreetingKt.class" to "compiled")
      // rules_android unpacks an AAR under the aar_import target's own name, not a Maven layout,
      // and the merged classes jar's bytes are not the published `.aar`'s.
      val unpacked = root.resolve("bazel-out/k8-fastbuild/bin/external/maven/_aar")
      val classes =
        jar(
          unpacked.resolve("androidx_activity_activity_compose/classes_and_libs_merged.jar"),
          "androidx/activity/compose/ComponentActivityKt.class" to "activity",
        )
      val aarR =
        jar(
          unpacked.resolve("androidx_activity_activity_compose/R.jar"),
          "androidx/activity/compose/R.class" to "r",
        )
      val owner = "@@rules_jvm_external++maven+maven//:androidx_activity_activity_compose"
      val lock =
        root.resolve("maven_install.json").apply {
          writeText(
            """
            |{"artifacts":{"androidx.activity:activity-compose:aar":
            |{"shasums":{"jar":"${"a".repeat(64)}"},"version":"1.10.1"}}}
            """
              .trimMargin()
          )
        }
      val preview =
        PreviewInfo("com.example.ui.GreetingKt.Greeting", "Greeting", "com.example.ui.GreetingKt")
      fun write(android: BazelAndroidResources?): BundleReader.Manifest =
        writeBazelBundle(
          "//app:ui",
          listOf(app),
          listOf(app, classes, aarR),
          PreviewManifest(
            "//app:ui",
            if (android != null) "android" else "desktop",
            listOf(preview),
          ),
          null,
          setOf(preview.id),
          listOf(lock),
          root.resolve("bundle.png"),
          embedDeps = false,
          android = android,
          dependencies =
            DependencyResolutionOptions.Builder()
              .apply { runtimeJarOwners = mapOf(classes to owner, aarR to owner) }
              .build(),
        )

      val apk = root.resolve("resources.ap_").apply { writeText("apk") }
      val manifest = root.resolve("AndroidManifest.xml").apply { writeText("<manifest/>") }
      val android = write(BazelAndroidResources("com.example.ui", apk, manifest, null))
      val maven = android.classpath.filterIsInstance<BundleReader.ClasspathEntry.Maven>()
      assertEquals(
        listOf("androidx.activity:activity-compose:1.10.1:aar"),
        maven.map { "${it.group}:${it.artifact}:${it.version}:${it.type}" },
      )
      // The target's second jar names the same coordinate; resolving it twice would lose its bytes.
      assertEquals(1, android.classpath.count { it is BundleReader.ClasspathEntry.Embedded })

      // A Desktop bundle keeps its pre-policy identification: owner labels only under a policy.
      val desktop = write(null)
      assertTrue(desktop.classpath.none { it is BundleReader.ClasspathEntry.Maven })
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `render output leaves ignore unsafe names`() {
    val json =
      """
      |{"previews":[
      |{"id":"a","captures":[{"renderOutput":"renders/A.png"}]},
      |{"id":"b","captures":[{"renderOutput":"renders/.."}]},
      |{"id":"c","captures":[]}]}
      """
        .trimMargin()
    assertEquals(mapOf("a" to "A.png"), renderOutputLeaves(json.toByteArray()))
    assertEquals(emptyMap(), renderOutputLeaves(null))
  }
}
