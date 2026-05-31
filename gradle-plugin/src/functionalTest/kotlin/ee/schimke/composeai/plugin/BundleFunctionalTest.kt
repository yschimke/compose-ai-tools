package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.discovery.*
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end coverage for `composePreviewBundle`:
 * 1. Bundle output is a valid PNG (file-magic + viewer compatibility).
 * 2. Bundle output is a valid zip (every reader finds the EOCD).
 * 3. Bundle includes `bundle.json`, `previews.json`, `classes/app.jar`, `report.json`.
 * 4. Selection works — packing one preview filters the manifest to that preview only.
 * 5. **Minimization is effective** — bundling one preview from a multi-preview module drops the
 *    other preview's class file from `classes/app.jar`.
 */
class BundleFunctionalTest {

  @get:Rule val tempDir = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Two preview files in different classes (`RedKt`, `BlueKt`) so we can prove minimization
   * actually drops the un-selected class.
   */
  private fun createTestProject(): File {
    val projectDir = tempDir.root

    File(projectDir, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            repositories {
                gradlePluginPortal()
                google()
                mavenCentral()
            }
        }
        dependencyResolutionManagement {
            repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
            repositories {
                google()
                mavenCentral()
            }
        }
        rootProject.name = "test-bundle"
        """
          .trimIndent()
      )

    File(projectDir, "build.gradle.kts")
      .writeText(
        """
        @file:Suppress("DEPRECATION")
        plugins {
            kotlin("jvm") version "2.2.21"
            kotlin("plugin.compose") version "2.2.21"
            id("org.jetbrains.compose") version "1.10.3"
            id("ee.schimke.composeai.preview")
        }
        dependencies {
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            implementation(compose.uiTooling)
            implementation(compose.components.uiToolingPreview)
        }
        java {
            toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
        }
        """
          .trimIndent()
      )

    File(projectDir, "gradle.properties").writeText("org.gradle.configuration-cache=true\n")

    val srcDir = File(projectDir, "src/main/kotlin/test")
    srcDir.mkdirs()
    File(srcDir, "Red.kt")
      .writeText(
        """
        package test

        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.foundation.background
        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.size
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.unit.dp

        @Preview
        @Composable
        fun RedBoxPreview() {
            Box(modifier = Modifier.size(100.dp).background(Color.Red))
        }
        """
          .trimIndent()
      )
    File(srcDir, "Blue.kt")
      .writeText(
        """
        package test

        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.foundation.background
        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.size
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.unit.dp

        @Preview
        @Composable
        fun BlueBoxPreview() {
            Box(modifier = Modifier.size(100.dp).background(Color.Blue))
        }
        """
          .trimIndent()
      )

    return projectDir
  }

  @Test
  fun `composePreviewBundle produces a PNG+ZIP polyglot`() {
    val projectDir = createTestProject()
    val redId = "test.RedKt.RedBoxPreview"

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewBundle", "-PbundlePreviewIds=$redId", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewBundle")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val bundle = File(projectDir, "build/compose-previews/bundle.png")
    assertThat(bundle.exists()).isTrue()
    val bytes = bundle.readBytes()

    // 1. PNG signature.
    assertThat(bytes.take(8))
      .containsExactly(
        (-119).toByte(),
        'P'.code.toByte(),
        'N'.code.toByte(),
        'G'.code.toByte(),
        '\r'.code.toByte(),
        '\n'.code.toByte(),
        26.toByte(),
        '\n'.code.toByte(),
      )
      .inOrder()

    // 2. ZIP EOCD signature appears somewhere in the trailing bytes.
    val zipEocd = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
    val eocdIndex = indexOf(bytes, zipEocd)
    assertThat(eocdIndex).isGreaterThan(0)

    // 3. Bundle entries — manifest + filtered previews + minimized module jar + audit report.
    val entries = listEntries(bundle)
    assertThat(entries)
      .containsAtLeast("bundle.json", "previews.json", "classes/app.jar", "report.json")
    // 4. No `libs/` directory: third-party jars must be listed as Maven coords, not inlined.
    //    (Project deps would land under `libs/`, but the synthetic test project has none.)
    val libsEntries = entries.filter { it.startsWith("libs/") }
    assertThat(libsEntries).isEmpty()
  }

  @Test
  fun `composePreviewBundle bakes a PNG per selected preview into previews dir`() {
    val projectDir = createTestProject()
    val redId = "test.RedKt.RedBoxPreview"
    val blueId = "test.BlueKt.BlueBoxPreview"

    // Phase 1 — discover so previews.json exists; that's where each capture's module-relative
    // renderOutput path comes from.
    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewDiscover")
      .withPluginClasspath()
      .build()

    val previewsJson = File(projectDir, "build/compose-previews/previews.json")
    assertThat(previewsJson.exists()).isTrue()
    val manifest = json.decodeFromString(PreviewManifest.serializer(), previewsJson.readText())

    // Phase 2 — seed a distinct dummy rendered PNG for each preview's primary capture, mimicking a
    // prior composePreviewRender. This exercises the baking path without needing the published
    // renderer-desktop artifact (composePreviewRender resolves it from Maven; unavailable here).
    val rendersDir = File(projectDir, "build/compose-previews/renders").apply { mkdirs() }
    val seeded = mutableMapOf<String, ByteArray>()
    var tint = 0
    for (preview in manifest.previews) {
      val name =
        preview.captures.first().renderOutput.substringAfterLast('/').ifEmpty {
          "${preview.id}.png"
        }
      val bytes = solidPng(0x202020 + (tint++ * 0x303030))
      File(rendersDir, name).writeBytes(bytes)
      seeded[preview.id] = bytes
    }

    // Phase 3 — pack both previews (red is the cover).
    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewBundle", "-PbundlePreviewIds=$redId,$blueId", "--stacktrace")
        .withPluginClasspath()
        .build()
    assertThat(result.task(":composePreviewBundle")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val bundle = File(projectDir, "build/compose-previews/bundle.png")
    val entries = listEntries(bundle)
    // Every selected preview is baked under the well-known previews/ directory, keyed by id.
    assertThat(entries).containsAtLeast("previews/$redId.png", "previews/$blueId.png")

    val coverEntry = readZipEntry(bundle, "previews/$redId.png")
    assertThat(coverEntry).isNotNull()
    assertThat(coverEntry!!.toList()).isEqualTo(seeded[redId]!!.toList())
    assertThat(readZipEntry(bundle, "previews/$blueId.png")!!.toList())
      .isEqualTo(seeded[blueId]!!.toList())

    // The polyglot's leading PNG (everything before the appended zip) is byte-identical to the
    // cover preview's baked entry — the front image is just a mirror of previews/<coverId>.png.
    val allBytes = bundle.readBytes()
    val leadingPng = allBytes.copyOfRange(0, allBytes.size - extractZipBytes(bundle).size)
    assertThat(leadingPng.toList()).isEqualTo(coverEntry.toList())
  }

  @Test
  fun `composePreviewBundle re-packs when renders appear after a render-less pack`() {
    val projectDir = createTestProject()
    val redId = "test.RedKt.RedBoxPreview"

    // `--build-cache` so the test asserts the *cache-correctness* property the renderFiles input
    // exists for, not just up-to-date checks. We assert on bundle CONTENT rather than task outcome:
    // outcome flips between SUCCESS / FROM_CACHE depending on whether a prior suite run warmed the
    // cache, but the regression (renders untracked) would leave the second pack UP_TO_DATE and the
    // bundle render-less — which the content assertions below catch deterministically either way.
    fun pack() {
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewBundle", "-PbundlePreviewIds=$redId", "--build-cache")
        .withPluginClasspath()
        .build()
    }

    // Pack once with no renders on disk: bundle is well-formed but bakes nothing.
    pack()
    var bundle = File(projectDir, "build/compose-previews/bundle.png")
    assertThat(listEntries(bundle).none { it.startsWith("previews/") }).isTrue()

    // Discover (for the renderOutput path), then seed a render and re-pack. Because the render PNGs
    // are tracked inputs (renderFiles), the task must NOT be UP-TO-DATE — it re-packs and bakes the
    // now-present PNG, rather than restoring the stale render-less bundle.
    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewDiscover")
      .withPluginClasspath()
      .build()
    val manifest =
      json.decodeFromString(
        PreviewManifest.serializer(),
        File(projectDir, "build/compose-previews/previews.json").readText(),
      )
    val redOutput = manifest.previews.first { it.id == redId }.captures.first().renderOutput
    val rendersDir = File(projectDir, "build/compose-previews/renders").apply { mkdirs() }
    File(rendersDir, redOutput.substringAfterLast('/')).writeBytes(solidPng(0x336699))

    pack()
    bundle = File(projectDir, "build/compose-previews/bundle.png")
    assertThat(listEntries(bundle)).contains("previews/$redId.png")
  }

  /** A tiny solid-colour PNG, distinct per [rgb], for seeding fake renders. */
  private fun solidPng(rgb: Int): ByteArray {
    val img = java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB)
    for (y in 0 until 2) for (x in 0 until 2) img.setRGB(x, y, rgb and 0xFFFFFF)
    val baos = java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(img, "png", baos)
    return baos.toByteArray()
  }

  @Test
  fun `composePreviewBundle filters previews_json to selected ids`() {
    val projectDir = createTestProject()
    val redId = "test.RedKt.RedBoxPreview"

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewBundle", "-PbundlePreviewIds=$redId")
      .withPluginClasspath()
      .build()

    val bundle = File(projectDir, "build/compose-previews/bundle.png")
    val previewsJson = readZipEntry(bundle, "previews.json")
    assertThat(previewsJson).isNotNull()
    val manifest =
      json.decodeFromString(PreviewManifest.serializer(), previewsJson!!.toString(Charsets.UTF_8))
    assertThat(manifest.previews.map { it.id }).containsExactly(redId)
  }

  @Test
  fun `minimization drops non-selected preview classes from the module jar`() {
    val projectDir = createTestProject()
    val redId = "test.RedKt.RedBoxPreview"

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewBundle", "-PbundlePreviewIds=$redId", "--stacktrace")
      .withPluginClasspath()
      .build()

    val bundle = File(projectDir, "build/compose-previews/bundle.png")
    val appJarBytes = readZipEntry(bundle, "classes/app.jar")
    assertThat(appJarBytes).isNotNull()

    val classesInAppJar = listEntries(appJarBytes!!).filter { it.endsWith(".class") }.toSet()
    // RedKt must be present (it's the selected preview's enclosing class).
    assertThat(classesInAppJar).contains("test/RedKt.class")
    // BlueKt must NOT be present — it's unreachable from RedBoxPreview's closure.
    assertThat(classesInAppJar).doesNotContain("test/BlueKt.class")
  }

  @Test
  fun `minimization report counts entry classes and dropped deps`() {
    val projectDir = createTestProject()
    val redId = "test.RedKt.RedBoxPreview"

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewBundle", "-PbundlePreviewIds=$redId")
      .withPluginClasspath()
      .build()

    val bundle = File(projectDir, "build/compose-previews/bundle.png")
    val reportBytes = readZipEntry(bundle, "report.json")
    assertThat(reportBytes).isNotNull()
    val report =
      json.decodeFromString(MinimizationReport.serializer(), reportBytes!!.toString(Charsets.UTF_8))

    assertThat(report.entryClassFqns).containsExactly("test.RedKt")
    // Module: 1 kept (RedKt) out of 2 (RedKt + BlueKt).
    assertThat(report.moduleClasses.totalClasses).isEqualTo(2)
    assertThat(report.moduleClasses.reachableClasses).isEqualTo(1)
    // Deps: at least one Maven dep contributed reachable classes (compose-runtime/ui/foundation).
    val kept = report.dependencies.count { it.kept }
    assertThat(kept).isGreaterThan(0)
    // And, crucially for the "small and shareable" goal: at least one dep was dropped — not every
    // transitive dep of compose-desktop is reachable from a single coloured box. If this fires,
    // closure is going way too wide.
    val dropped = report.dependencies.count { !it.kept }
    assertThat(dropped).isGreaterThan(0)
  }

  @Test
  fun `bundle manifest lists Maven coordinates not inlined jars`() {
    val projectDir = createTestProject()
    val redId = "test.RedKt.RedBoxPreview"

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewBundle", "-PbundlePreviewIds=$redId")
      .withPluginClasspath()
      .build()

    val bundle = File(projectDir, "build/compose-previews/bundle.png")
    val bundleJsonBytes = readZipEntry(bundle, "bundle.json")
    assertThat(bundleJsonBytes).isNotNull()
    val classpathArray =
      json
        .parseToJsonElement(bundleJsonBytes!!.toString(Charsets.UTF_8))
        .jsonObject["classpath"]!!
        .jsonArray

    val kinds = classpathArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content }
    // First entry is always the inlined consumer module.
    assertThat(kinds.first()).isEqualTo("module")
    // The rest should be Maven coords — synthetic test project has no project deps.
    val nonModuleKinds = kinds.drop(1).toSet()
    assertThat(nonModuleKinds).contains("maven")
    assertThat(nonModuleKinds).doesNotContain("project")

    // Spot-check at least one well-formed Maven entry (compose-runtime is a near-certainty).
    val mavenEntries = classpathArray.filter {
      it.jsonObject["kind"]!!.jsonPrimitive.content == "maven"
    }
    assertThat(mavenEntries).isNotEmpty()
    val first = mavenEntries.first().jsonObject
    assertThat(first["group"]!!.jsonPrimitive.content).isNotEmpty()
    assertThat(first["artifact"]!!.jsonPrimitive.content).isNotEmpty()
    assertThat(first["version"]!!.jsonPrimitive.content).isNotEmpty()
    assertThat(first["type"]!!.jsonPrimitive.content).isEqualTo("jar")
    // v4: every referenced (detached) coordinate carries a content hash so a player can verify the
    // bytes after re-resolving from any source. 64 lowercase hex chars = SHA-256.
    val sha = first["sha256"]!!.jsonPrimitive.content
    assertThat(sha).matches("[0-9a-f]{64}")
  }

  @Test
  fun `embed-deps pack carries reachable jars in libs and marks resolution embedded`() {
    val projectDir = createTestProject()
    val redId = "test.RedKt.RedBoxPreview"

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewBundle", "-PbundlePreviewIds=$redId", "-PbundleEmbedDeps=true")
      .withPluginClasspath()
      .build()

    val bundle = File(projectDir, "build/compose-previews/bundle.png")
    val entries = listEntries(bundle)
    // Embedded mode carries the reachable third-party jars inside the bundle's `libs/`.
    val libsEntries = entries.filter { it.startsWith("libs/") && it.endsWith(".jar") }
    assertThat(libsEntries).isNotEmpty()

    val manifest =
      json
        .parseToJsonElement(readZipEntry(bundle, "bundle.json")!!.toString(Charsets.UTF_8))
        .jsonObject
    assertThat(manifest["schemaVersion"]!!.jsonPrimitive.content).isEqualTo("3")
    assertThat(manifest["resolution"]!!.jsonPrimitive.content).isEqualTo("embedded")
    assertThat(manifest["producer"]!!.jsonPrimitive.content).isEqualTo("gradle")

    val kinds =
      manifest["classpath"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content }
    // First entry is still the inlined consumer module; the third-party deps are now `embedded`,
    // not `maven` — that's the whole point of the mode.
    assertThat(kinds.first()).isEqualTo("module")
    assertThat(kinds).contains("embedded")
    assertThat(kinds).doesNotContain("maven")

    // Every `embedded` entry's `inlinedAs` must point at a real `libs/` zip entry.
    val embeddedPaths =
      manifest["classpath"]!!
        .jsonArray
        .filter { it.jsonObject["kind"]!!.jsonPrimitive.content == "embedded" }
        .map { it.jsonObject["inlinedAs"]!!.jsonPrimitive.content }
    assertThat(embeddedPaths).isNotEmpty()
    assertThat(entries).containsAtLeastElementsIn(embeddedPaths)
  }

  @Test
  fun `default pack stays coordinates with no libs directory`() {
    val projectDir = createTestProject()
    val redId = "test.RedKt.RedBoxPreview"

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewBundle", "-PbundlePreviewIds=$redId")
      .withPluginClasspath()
      .build()

    val bundle = File(projectDir, "build/compose-previews/bundle.png")
    val manifest =
      json
        .parseToJsonElement(readZipEntry(bundle, "bundle.json")!!.toString(Charsets.UTF_8))
        .jsonObject
    assertThat(manifest["resolution"]!!.jsonPrimitive.content).isEqualTo("coordinates")
    assertThat(listEntries(bundle).filter { it.startsWith("libs/") }).isEmpty()
  }

  private fun listEntries(file: File): Set<String> = listEntries(extractZipBytes(file))

  private fun listEntries(zipBytes: ByteArray): Set<String> {
    val names = mutableSetOf<String>()
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        names += entry.name
        zin.closeEntry()
      }
    }
    return names
  }

  private fun readZipEntry(file: File, name: String): ByteArray? =
    readZipEntry(extractZipBytes(file), name)

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

  private fun extractZipBytes(file: File): ByteArray {
    val bytes = file.readBytes()
    if (bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) return bytes
    // PNG header — walk chunks to IEND.
    var offset = 8
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
    error("PNG IEND not found in $file")
  }

  private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
    outer@ for (i in 0..haystack.size - needle.size) {
      for (j in needle.indices) {
        if (haystack[i + j] != needle[j]) continue@outer
      }
      return i
    }
    return -1
  }
}
