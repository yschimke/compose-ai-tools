package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectBuildTest {
  private fun fixture(block: (File) -> Unit) {
    val root = Files.createTempDirectory("project-build").toFile().canonicalFile
    try {
      root.resolve(".git").mkdir()
      block(root)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `a Lightbuild project routes to its generated Gradle build`() = fixture { root ->
    root.resolve(LIGHTBUILD_PROJECT_FILE).writeText("")
    val generated = root.resolve(LIGHTBUILD_GRADLE_DIR).apply { mkdirs() }
    generated.resolve("settings.gradle").writeText("")
    val app = root.resolve("app/src").apply { mkdirs() }
    assertEquals(ProjectBuild(ProjectBuildKind.GRADLE, generated), detectProjectBuild(app))
  }

  @Test
  fun `Gradle wins over BUILD and workspace markers in the same build`() = fixture { root ->
    root.resolve("settings.gradle.kts").writeText("")
    root.resolve("MODULE.bazel").writeText("")
    val app = root.resolve("app/src").apply { mkdirs() }
    app.parentFile.resolve("BUILD").writeText("")
    assertEquals(ProjectBuild(ProjectBuildKind.GRADLE, root), detectProjectBuild(app))
    assertEquals(
      ProjectBuildKind.BAZEL,
      previewBuildRoute(listOf("--build-system", "bazel"), app)?.kind,
    )
  }

  @Test
  fun `nested Bazel workspace prevents using outer Gradle settings`() = fixture { root ->
    root.resolve("settings.gradle").writeText("")
    val nested =
      root.resolve("nested").apply {
        mkdirs()
        resolve("WORKSPACE").writeText("")
      }
    val app =
      nested.resolve("app").apply {
        mkdir()
        resolve("BUILD.bazel").writeText("")
      }
    assertEquals(ProjectBuild(ProjectBuildKind.BAZEL, nested), detectProjectBuild(app))
    assertEquals(
      ProjectBuildKind.GRADLE,
      previewBuildRoute(listOf("--build-system", "gradle"), app)?.kind,
    )
  }

  @Test
  fun `settings in nested Gradle build win over outer Bazel workspace`() = fixture { root ->
    root.resolve("MODULE.bazel").writeText("")
    val nested =
      root.resolve("nested").apply {
        mkdir()
        resolve("settings.gradle.kts").writeText("")
      }
    assertEquals(ProjectBuild(ProjectBuildKind.GRADLE, nested), detectProjectBuild(nested))
  }

  @Test
  fun `BUILD-only and WORKSPACE builds are detected without executing tools`() = fixture { root ->
    val app =
      root.resolve("app").apply {
        mkdir()
        resolve("BUILD").writeText("")
      }
    assertEquals(ProjectBuild(ProjectBuildKind.BAZEL, root), detectProjectBuild(app))
    root.resolve("WORKSPACE").writeText("")
    assertEquals(ProjectBuild(ProjectBuildKind.BAZEL, root), detectProjectBuild(app))
    assertEquals(root, detectBazelRoot(app))
    assertEquals("bazel", selectBazelBuildCommand(root, "", override = null).command)
    // A custom wrapper is named by the environment, not by a workspace marker.
    assertEquals(
      "/opt/bazel-wrapper",
      selectBazelBuildCommand(root, "", override = "/opt/bazel-wrapper").command,
    )
  }

  @Test
  fun `blazerc is not a workspace marker and does not pick the build command`() = fixture { root ->
    val nested = root.resolve("nested").apply { mkdir() }
    nested.resolve(".blazerc").writeText("")
    assertNull(detectProjectBuild(nested))
    assertNull(detectBazelRoot(nested))
    assertFailsWith<IllegalStateException> {
      previewBuildRoute(listOf("--build-system", "bazel"), nested)
    }
    // A BUILD file elsewhere still makes it a Bazel build, rooted at the VCS root, not at .blazerc.
    nested.resolve("BUILD").writeText("")
    assertEquals(ProjectBuild(ProjectBuildKind.BAZEL, root), detectProjectBuild(nested))
    val bin =
      root.resolve("bin").apply {
        mkdir()
        resolve("bazel").writeText("")
        resolve("bazel").setExecutable(true)
      }
    assertEquals("bazel", selectBazelBuildCommand(nested, bin.path, override = null).command)
    assertEquals("bazel", selectBazelBuildCommand(nested, "", override = null).command)
  }

  @Test
  fun `blaze is no longer a build system name`() = fixture { root ->
    root.resolve("MODULE.bazel").writeText("")
    val error =
      assertFailsWith<IllegalStateException> {
        previewBuildRoute(listOf("--build-system", "blaze"), root)
      }
    assertTrue(error.message!!.contains("expected auto, gradle, bazel or buck"))
  }

  @Test
  fun `wrapper fallback is preserved and unrelated ancestor builds are ignored`() =
    fixture { root ->
      assertNull(detectProjectBuild(root))
      root.resolve("gradlew").writeText("")
      assertEquals(ProjectBuild(ProjectBuildKind.GRADLE, root), detectProjectBuild(root))
      assertFailsWith<IllegalStateException> {
        previewBuildRoute(listOf("--build-system=unknown"), root)
      }
    }

  @Test
  fun `Bazelisk is used if Bazel is absent and explicit project is honored`() = fixture { root ->
    root.resolve("BUILD.bazel").writeText("")
    val bin =
      root.resolve("bin").apply {
        mkdir()
        resolve("bazelisk").writeText("")
        resolve("bazelisk").setExecutable(true)
      }
    assertEquals("bazelisk", selectBazelBuildCommand(root, bin.path, override = null).command)
    assertEquals(root, previewBuildRoute(listOf("--project", root.path), bin)?.root)
  }

  @Test
  fun `bazelversion prefers bazelisk and the environment override wins over detection`() =
    fixture { root ->
      root.resolve("MODULE.bazel").writeText("")
      val bin =
        root.resolve("bin").apply {
          mkdir()
          listOf("bazel", "bazelisk").forEach {
            resolve(it).writeText("")
            resolve(it).setExecutable(true)
          }
        }
      assertEquals("bazel", selectBazelBuildCommand(root, bin.path, override = null).command)
      root.resolve(".bazelversion").writeText("9.2.0\n")
      val pinned = selectBazelBuildCommand(root, bin.path, override = null)
      assertEquals("bazelisk", pinned.command)
      assertTrue(pinned.reason.contains(".bazelversion"))
      val overridden = selectBazelBuildCommand(root, bin.path, override = "/opt/wrapper")
      assertEquals("/opt/wrapper", overridden.command)
      assertTrue(overridden.reason.contains(BUILD_COMMAND_ENV))
    }

  @Test
  fun `query stays in the nearest package and selects all of its targets`() = fixture { root ->
    root.resolve("MODULE.bazel").writeText("")
    val app =
      root.resolve("app").apply {
        mkdir()
        resolve("BUILD").writeText("")
      }
    var query: List<String> = emptyList()
    val build = BazelBuild { argv, _, _ ->
      query = argv
      "//app:ui\n"
    }
    assertEquals(
      BazelTargetSelection("//app:ui", listOf("//app:ui")),
      build.selectTargets(root, app, BazelBuildConfig(), 20),
    )
    assertTrue(query.any { it.contains("//app:*") })
    assertTrue(query.none { "..." in it })
    // Several targets in the package: all of them, named as `//app:all` selects them.
    val several = BazelBuild { _, _, _ -> "//app:ui\n//app:model\n" }
    assertEquals(
      BazelTargetSelection("//app:all", listOf("//app:model", "//app:ui")),
      several.selectTargets(root, app, BazelBuildConfig(), 20),
    )
    val none = BazelBuild { _, _, _ -> "" }
    assertTrue(
      assertFailsWith<IllegalStateException> {
          none.selectTargets(root, app, BazelBuildConfig(), 20)
        }
        .message!!
        .contains("--target")
    )
    val configured = BazelBuild { _, _, _ -> error("Configured target must not query") }
    assertEquals(
      BazelTargetSelection("//app:ui", listOf("//app:ui")),
      configured.selectTargets(root, app, BazelBuildConfig(target = "//app:ui"), 20),
    )
  }
}
