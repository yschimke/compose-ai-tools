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
  fun `BUILD-only and Blaze workspaces are detected without executing tools`() = fixture { root ->
    val app =
      root.resolve("app").apply {
        mkdir()
        resolve("BUILD").writeText("")
      }
    assertEquals(ProjectBuild(ProjectBuildKind.BAZEL, root), detectProjectBuild(app))
    root.resolve(".blazerc").writeText("")
    assertEquals(ProjectBuild(ProjectBuildKind.BAZEL, root), detectProjectBuild(app))
    assertEquals("blaze", defaultBazelBuildCommand(root, ""))
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
    assertEquals("bazelisk", defaultBazelBuildCommand(root, bin.path))
    assertEquals(root, previewBuildRoute(listOf("--project", root.path), bin)?.root)
  }

  @Test
  fun `query stays in the nearest package and refuses an ambiguous target`() = fixture { root ->
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
    assertEquals("//app:ui", build.selectTarget(root, app, BazelBuildConfig(), 20))
    assertTrue(query.any { it.contains("//app:*") })
    assertTrue(query.none { "..." in it })
    val ambiguous = BazelBuild { _, _, _ -> "//app:ui\n//app:test\n" }
    assertTrue(
      assertFailsWith<IllegalStateException> {
          ambiguous.selectTarget(root, app, BazelBuildConfig(), 20)
        }
        .message!!
        .contains("--target")
    )
    val configured = BazelBuild { _, _, _ -> error("Configured target must not query") }
    assertEquals(
      "//app:ui",
      configured.selectTarget(root, app, BazelBuildConfig(target = "//app:ui"), 20),
    )
  }
}
