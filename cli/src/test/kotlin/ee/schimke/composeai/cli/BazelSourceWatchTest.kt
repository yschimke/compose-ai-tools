package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #6193: a Bazel or Buck2 descriptor says where its target is built from. */
class BazelSourceWatchTest {
  private fun File.touch(text: String = ""): File = apply {
    parentFile.mkdirs()
    writeText(text)
  }

  private fun artifacts(
    target: String,
    sources: List<File>,
    android: ResolvedAndroidArtifacts? = null,
  ) =
    ResolvedBazelArtifacts(
      metadata = BazelArtifacts(target, "config", emptyList(), emptyList()),
      projectJars = emptyList(),
      runtimeJars = emptyList(),
      sourceFiles = sources,
      dataFiles = emptyList(),
      android = android,
    )

  @Test
  fun `workspace source dirs, the package's res and its build files are watched`() {
    val root = Files.createTempDirectory("bazel-source-watch").toFile()
    try {
      val screen = root.resolve("feature/ui/src/main/kotlin/ui/Screen.kt").touch()
      val theme = root.resolve("feature/ui/src/main/kotlin/ui/theme/Theme.kt").touch()
      val shared = root.resolve("feature/ui/shared/Shared.kt").touch()
      // Generated and external sources: nobody edits them.
      val generated = root.resolve("bazel-out/k8-fastbuild/bin/feature/ui/Gen.kt").touch()
      val external = Files.createTempDirectory("bazel-external").toFile().resolve("Ext.kt").touch()
      val build = root.resolve("feature/ui/BUILD.bazel").touch()
      val res = root.resolve("feature/ui/res").apply { mkdirs() }
      val config = root.resolve(".compose-preview/bazel.json").touch("{}")
      val android =
        ResolvedAndroidArtifacts("ui", root.resolve("r.apk"), root.resolve("m.xml"), null, 35)

      val watch =
        bazelSourceWatch(
          artifacts("//feature/ui:ui", listOf(screen, theme, shared, generated, external), android),
          root,
        )!!

      // The package, not each source's directory: a Kotlin package added beside these is watched.
      assertEquals(listOf(build.parentFile.path), watch.sourceDirs)
      assertEquals(listOf(res.path), watch.resourceDirs)
      assertEquals(listOf(build.path, config.path), watch.buildFiles)
      // A desktop target has no Android res/.
      assertEquals(
        emptyList(),
        bazelSourceWatch(artifacts("//feature/ui:ui", listOf(screen)), root)!!.resourceDirs,
      )
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `a target with nothing in the workspace publishes no watch`() {
    val root = Files.createTempDirectory("bazel-source-watch-none").toFile()
    try {
      val generated = root.resolve("bazel-out/bin/Gen.kt").touch()
      assertNull(bazelSourceWatch(artifacts("@maven//:lib", listOf(generated)), root))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `a label's package is read for Bazel and Buck2 alike`() {
    assertEquals("a/b", bazelLabelPackage("//a/b:c"))
    assertEquals("a/b", bazelLabelPackage("@//a/b:c"))
    assertEquals("a/b", bazelLabelPackage("root//a/b:c"))
    assertEquals("", bazelLabelPackage("//:app"))
    assertNull(bazelLabelPackage("@maven//:lib"))
  }

  /** #6194 review: a merged selection's label names no one package; its sources do. */
  @Test
  fun `a merged selection watches every package its sources come from`() {
    val root = Files.createTempDirectory("bazel-source-watch-merged").toFile()
    try {
      val a = root.resolve("a/src/A.kt").touch()
      val b = root.resolve("b/B.kt").touch()
      val buildA = root.resolve("a/BUILD.bazel").touch()
      val buildB = root.resolve("b/BUILD").touch()
      val resB = root.resolve("b/res").apply { mkdirs() }
      val android =
        ResolvedAndroidArtifacts("a", root.resolve("r.apk"), root.resolve("m.xml"), null, 35)

      val watch = bazelSourceWatch(artifacts("//a:a //b:b", listOf(a, b), android), root)!!

      assertEquals(listOf(buildA.parentFile.path, buildB.parentFile.path), watch.sourceDirs)
      assertEquals(listOf(resB.path), watch.resourceDirs)
      assertEquals(listOf(buildA.path, buildB.path), watch.buildFiles)
    } finally {
      root.deleteRecursively()
    }
  }

  /** A package at the workspace root would watch the output-tree symlinks, so its dirs are used. */
  @Test
  fun `a root package watches its sources' directories`() {
    val root = Files.createTempDirectory("bazel-source-watch-root").toFile()
    try {
      val app = root.resolve("src/App.kt").touch()
      val build = root.resolve("BUILD.bazel").touch()

      val watch = bazelSourceWatch(artifacts("//:app", listOf(app)), root)!!

      assertEquals(listOf(app.parentFile.path), watch.sourceDirs)
      assertEquals(listOf(build.path), watch.buildFiles)
    } finally {
      root.deleteRecursively()
    }
  }

  /** #6197 review: a wrapper that only re-exports another package's library keeps its own. */
  @Test
  fun `an export-only target keeps its own package`() {
    val root = Files.createTempDirectory("bazel-source-watch-wrapper").toFile()
    try {
      val lib = root.resolve("lib/Lib.kt").touch()
      val libBuild = root.resolve("lib/BUILD").touch()
      val appBuild = root.resolve("app/BUILD.bazel").touch()
      val appRes = root.resolve("app/res").apply { mkdirs() }
      val android =
        ResolvedAndroidArtifacts("app", root.resolve("r.apk"), root.resolve("m.xml"), null, 35)

      val watch = bazelSourceWatch(artifacts("//app:app", listOf(lib), android), root)!!

      assertEquals(listOf(libBuild.parentFile.path), watch.sourceDirs)
      assertEquals(listOf(appRes.path), watch.resourceDirs)
      assertEquals(listOf(appBuild.path, libBuild.path), watch.buildFiles)
    } finally {
      root.deleteRecursively()
    }
  }
}
