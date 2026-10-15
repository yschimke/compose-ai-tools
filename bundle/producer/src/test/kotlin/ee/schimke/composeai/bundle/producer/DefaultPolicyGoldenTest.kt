package ee.schimke.composeai.bundle.producer

import ee.schimke.composeai.discovery.Capture
import ee.schimke.composeai.discovery.PreviewInfo
import ee.schimke.composeai.discovery.PreviewManifest
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the bytes a Bazel bundle had before `dependencyPolicy` existed (#6016). The golden hash was
 * computed with the pre-policy producer, so a default (unconfigured) bundle must stay identical: no
 * existing user's bundle may change because the policy machinery was added.
 */
class DefaultPolicyGoldenTest {

  @Test
  fun `default policy output is byte identical to the pre-policy producer`() {
    val root = Files.createTempDirectory("policy-golden").toFile()
    try {
      val fixture = PolicyFixture(root)
      val bundle = root.resolve("bundle.png")
      fixture.write(bundle)
      assertEquals(PRE_POLICY_DECISIONS, fixture.decisions(bundle))
      assertEquals(PRE_POLICY_BUNDLE_SHA, bazelBundleSha(bundle))
      // The policy-aware entry point with the default options is the same bundle, owners or not:
      // owner labels and version stamps are read only under a configured policy.
      val viaOptions = root.resolve("options.png")
      fixture.write(
        viaOptions,
        DependencyResolutionOptions(
          runtimeJarOwners = mapOf(fixture.local to "@maven//:com_example_runtime")
        ),
      )
      assertEquals(PRE_POLICY_BUNDLE_SHA, bazelBundleSha(viaOptions))
    } finally {
      root.deleteRecursively()
    }
  }

  companion object {
    const val PRE_POLICY_BUNDLE_SHA: String =
      "2d7fd2809feb64c3d8a7f80bd9e214d3460de2102c6ce5c14c7862764495358c"
    const val PRE_POLICY_DECISIONS: String =
      """[{"builtSha256":"dd6aa92b6b6b5b8163510d1ebe257a34ee3ececd2e341011c641b5f2338ebff2","coordinate":"org.jetbrains.compose.ui:ui:1.0","jar":"processed_ui.jar","method":"closest-lockfile-artifact","publishedSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},{"builtSha256":"7ddc2db403f1539b81c67b393b896f2248deff684b98cc3acf524130f4e9d7c9","coordinate":"org.jetbrains.kotlin:kotlin-stdlib:2.0.0","jar":"kotlin-stdlib-2.0.0.jar","method":"lockfile-name","publishedSha256":"7ddc2db403f1539b81c67b393b896f2248deff684b98cc3acf524130f4e9d7c9"},{"builtSha256":"6a908b6f647f34021084a062588f936dd3bcf86a3eb7eff60bac1bbe75788352","coordinate":"com.example:lib:3.1","jar":"vendored.jar","method":"pom-properties","publishedSha256":null},{"builtSha256":"cd72318631c64312aa2bb12258d42f18baf0b7babe6d91c0c1f671a1c7a138a0","coordinate":null,"jar":"ui-android.jar","method":"unidentified-embedded","publishedSha256":null},{"builtSha256":"d38aa301ff938f4247170fd77e6963581f3c6db93f12c2a1a7591021e5153ced","coordinate":null,"jar":"runtime.jar","method":"unidentified-embedded","publishedSha256":null}]"""
  }
}

/** Deterministic Bazel-shaped inputs covering every identification method and an unmatched jar. */
internal class PolicyFixture(root: File) {
  private fun jar(file: File, entries: List<Pair<String, String>>): File = file.apply {
    parentFile.mkdirs()
    ZipOutputStream(outputStream()).use { zip ->
      entries.forEach { (name, value) ->
        zip.putNextEntry(ZipEntry(name).apply { setTimeLocal(LocalDateTime.of(2020, 1, 1, 0, 0)) })
        zip.write(value.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
      }
    }
  }

  val app: File = jar(root.resolve("bin/app.jar"), listOf("App.class" to "compiled"))

  /** Bazel's `jvm_import` stamps a manifest: the bytes never match the published sha. */
  val processed: File =
    jar(
      root.resolve("external/maven/processed_ui.jar"),
      listOf("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\nTarget-Label: @maven//:ui\n"),
    )

  /** Exact lockfile name and checksum. */
  val stdlib: File =
    jar(root.resolve("external/maven/kotlin-stdlib-2.0.0.jar"), listOf("K.class" to "stdlib"))

  /** Identified only by `pom.properties`, unpinned. */
  val pomOnly: File =
    jar(
      root.resolve("libs/vendored.jar"),
      listOf(
        "META-INF/maven/com.example/lib/pom.properties" to
          "groupId=com.example\nartifactId=lib\nversion=3.1\n",
        "L.class" to "lib",
      ),
    )

  /** A top-of-tree androidx build: a version stamp and nothing else. */
  val stamped: File =
    jar(
      root.resolve("prebuilts/androidx/ui-android.jar"),
      listOf(
        "META-INF/androidx.compose.ui_ui.version" to "1.14.0-alpha01\n",
        "androidx/compose/ui/Modifier.class" to "ui",
      ),
    )

  /** No identification at all. */
  val local: File = jar(root.resolve("bin/runtime.jar"), listOf("runtime.txt" to "runtime-only"))

  val lock: File =
    root.resolve("maven_install.json").apply {
      writeText(
        """
        {"artifacts":{
          "org.jetbrains.compose.ui:ui":{"version":"1.0","shasums":{"jar":"${"a".repeat(64)}"}},
          "org.jetbrains.kotlin:kotlin-stdlib":{"version":"2.0.0","shasums":{"jar":"${bazelBundleSha(stdlib)}"}}
        }}
        """
          .trimIndent()
      )
    }

  val preview: PreviewInfo =
    PreviewInfo(
      "example.App.Greeting",
      "Greeting",
      "example.App",
      sourceFile = "src/App.kt",
      captures = listOf(Capture(renderOutput = "greeting.png")),
    )

  val manifest: PreviewManifest = PreviewManifest("//:app", "desktop", listOf(preview))

  val runtime: List<File> = listOf(app, processed, stdlib, pomOnly, stamped, local)

  fun write(output: File) {
    writeBazelBundle(
      "//:app",
      listOf(app),
      runtime,
      manifest,
      null,
      setOf(preview.id),
      listOf(lock),
      output,
    )
  }

  fun write(
    output: File,
    dependencies: DependencyResolutionOptions,
    embedDeps: Boolean = false,
  ): ee.schimke.composeai.bundle.BundleReader.Manifest =
    writeBazelBundle(
      "//:app",
      listOf(app),
      runtime,
      manifest,
      null,
      setOf(preview.id),
      listOf(lock),
      output,
      embedDeps,
      null,
      dependencies,
    )

  fun entry(bundle: File, name: String): String {
    java.util.zip
      .ZipInputStream(
        java.io.ByteArrayInputStream(
          ee.schimke.composeai.bundle.BundleReader.extractZipBytes(bundle)
        )
      )
      .use { zip ->
        while (true) {
          val entry = zip.nextEntry ?: break
          if (entry.name == name) return zip.readBytes().decodeToString()
        }
      }
    error("missing $name")
  }

  fun decisions(bundle: File): String = entry(bundle, "dependency-resolution.json")
}
