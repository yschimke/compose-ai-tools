package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Several selected Android targets as one module: one resource APK linked over their union, and the
 * R classes that go with it. The link itself is rules_compose_preview's `link.sh`; here a stand-in
 * script records what it was asked to do.
 */
class BazelAndroidMergeTest {
  private val root: File = Files.createTempDirectory("bazel-android-merge").toFile()

  @AfterTest
  fun tearDown() {
    root.deleteRecursively()
  }

  private fun file(name: String, text: String = name): File =
    root.resolve(name).apply {
      parentFile.mkdirs()
      writeText(text)
    }

  private fun link(
    packages: List<String>,
    archives: List<String>,
    assets: List<Pair<String, String>>,
  ) =
    ResolvedAndroidLink(
      script = file("link.sh"),
      aapt2 = file("sdk/aapt2"),
      javaHome = root.resolve("jdk").apply { mkdirs() },
      androidJar = file("sdk/android.jar"),
      packages = packages,
      compiledResources = archives.map { file(it) },
      assets = assets.map { (path, name) -> path to file(name) },
    )

  private fun target(
    label: String,
    own: String,
    pkg: String,
    link: ResolvedAndroidLink?,
    deps: List<String> = emptyList(),
  ): ResolvedBazelArtifacts {
    val rJar = file("$own-r-final.jar")
    val runtime = listOf("$own-r-final.jar", "$own.jar") + deps
    return BazelArtifacts(label, "k8", listOf("$own.jar"), runtime)
      .also { runtime.forEach { file(it) } }
      .resolve(root)
      .copy(
        android = ResolvedAndroidArtifacts(pkg, file("$own.ap_"), file("$own.xml"), rJar, 35, link)
      )
  }

  @Test
  fun `the overlay order keeps every target's own, shared dependencies once`() {
    assertEquals(
      listOf("theme", "ui", "feature"),
      mergeOverlayOrder(listOf(listOf("theme", "ui"), listOf("theme", "feature"))),
    )
    // A dependency first seen in a later target still goes before everything that depends on it.
    assertEquals(
      listOf("base", "a", "b", "ui", "feature"),
      mergeOverlayOrder(listOf(listOf("a", "b", "ui"), listOf("base", "a", "feature"))),
    )
    // Unrelated siblings keep selection order.
    assertEquals(listOf("x", "y"), mergeOverlayOrder(listOf(listOf("x"), listOf("y"))))
  }

  @Test
  fun `two Android libraries become one module on one linked APK`() {
    val ui =
      target(
        "//ui",
        "ui",
        "com.example.ui",
        link(
          listOf("com.example.ui", "com.example.theme"),
          listOf("theme.zip", "ui.zip"),
          listOf("shared.txt" to "theme-shared.txt", "ui-only.txt" to "ui-only.txt"),
        ),
        deps = listOf("theme.jar", "compose.jar"),
      )
    val feature =
      target(
        "//feature",
        "feature",
        "com.example.feature",
        link(
          listOf("com.example.feature", "com.example.theme"),
          listOf("theme.zip", "feature.zip"),
          listOf("shared.txt" to "feature-shared.txt"),
        ),
        deps = listOf("theme.jar"),
      )
    val linked =
      ResolvedAndroidArtifacts(
        "com.example.ui",
        file("merged.ap_"),
        file("merged.xml"),
        file("merged-r.jar"),
        35,
      )
    var plan: AndroidMergePlan? = null

    val merged =
      assertNotNull(
        mergeBazelArtifacts("//...", listOf(ui, feature)) {
          plan = it
          linked
        }
      )

    val asked = assertNotNull(plan)
    assertEquals("com.example.ui", asked.packageName)
    assertEquals(listOf("com.example.theme", "com.example.feature"), asked.extraPackages)
    assertEquals(
      listOf("theme.zip", "ui.zip", "feature.zip"),
      asked.compiledResources.map { it.name },
    )
    // The later target's asset at the same path wins, as a later overlay does.
    assertEquals(
      listOf("shared.txt" to "feature-shared.txt", "ui-only.txt" to "ui-only.txt"),
      asked.assets.map { (path, file) -> path to file.name },
    )
    assertEquals(linked, merged.android)
    assertEquals(listOf("ui.jar", "feature.jar"), merged.projectJars.map { it.name })
    // Each target's own R jar carries IDs for its own APK only; the merged one leads instead.
    assertEquals(
      listOf("merged-r.jar", "ui.jar", "theme.jar", "compose.jar", "feature.jar"),
      merged.runtimeJars.map { it.name },
    )
    val metadata = assertNotNull(merged.metadata.android)
    assertEquals("com.example.ui", metadata.packageName)
    assertEquals(linked.resourceApk.absolutePath, metadata.resourceApk)
    assertEquals(
      merged.runtimeJars.map { it.absolutePath }.first(),
      merged.metadata.runtimeJars.first(),
    )
  }

  @Test
  fun `Android targets stay apart without link inputs, and never join JVM targets`() {
    val withLink =
      target(
        "//ui",
        "ui",
        "com.example.ui",
        link(listOf("com.example.ui"), listOf("ui.zip"), emptyList()),
      )
    val withoutLink = target("//old", "old", "com.example.old", link = null)
    val jvm =
      BazelArtifacts("//model", "k8", listOf("model.jar"), listOf("model.jar"))
        .also { file("model.jar") }
        .resolve(root)

    assertTrue(bazelMergeBlocker(listOf(withLink, withoutLink))!!.contains("link inputs"))
    assertNull(mergeBazelArtifacts("//...", listOf(withLink, withoutLink)) { error("not linked") })
    assertTrue(bazelMergeBlocker(listOf(withLink, jvm))!!.contains("mix Android and JVM"))
    assertNull(mergeBazelArtifacts("//...", listOf(withLink, jvm)) { error("not linked") })
  }

  @Test
  fun `the merged link runs link sh once and is reused while its inputs are unchanged`() {
    val calls = root.resolve("calls.txt")
    val tools = link(listOf("com.example.ui"), listOf("ui.zip"), listOf("a.txt" to "asset.txt"))
    // Records its arguments and writes the two outputs, as link.sh does.
    val script =
      file(
        "link.sh",
        """
        echo "$*" >> "${calls.absolutePath}"
        cp "${'$'}5" "${'$'}5.seen"
        echo apk > "${'$'}3"
        echo jar > "${'$'}4"
        """
          .trimIndent(),
      )
    val withScript = tools.copy(script = script)
    val plan =
      AndroidMergePlan(
        packageName = "com.example.ui",
        extraPackages = listOf("com.example.theme", "com.example.feature"),
        compiledResources = listOf(file("theme.zip"), file("ui.zip")),
        assets = listOf("a.txt" to file("asset.txt")),
        compileSdk = 35,
        tools = withScript,
        replacedRJars = emptySet(),
      )
    val out = root.resolve("out")

    val linked = linkAndroidMerge(plan, out)

    assertTrue(linked.resourceApk.isFile && linked.rJar!!.isFile)
    assertEquals("com.example.ui", linked.packageName)
    assertTrue(linked.manifest.readText().contains("package=\"com.example.ui\""))
    val args = calls.readLines().single().split(" ")
    assertEquals("com.example.theme:com.example.feature", args[6])
    assertEquals(listOf("theme.zip", "ui.zip"), args.drop(8).map { File(it).name })
    assertEquals("${root.resolve("asset.txt").absolutePath}\ta.txt\n", File(args[7]).readText())

    linkAndroidMerge(plan, out)
    assertEquals(1, calls.readLines().size, "unchanged inputs reuse the link")
    root.resolve("ui.zip").writeText("changed resources")
    linkAndroidMerge(plan, out)
    assertEquals(2, calls.readLines().size, "a changed archive relinks")
  }
}
