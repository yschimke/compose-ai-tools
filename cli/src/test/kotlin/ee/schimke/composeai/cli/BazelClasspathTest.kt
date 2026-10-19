package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The runtime classpath pruning a Bazel Android target needs: one empty wrapper and one non-final R
 * jar per AAR (compose-ai-contrib#60), which the final-ID R jar and nothing else should replace.
 */
class BazelClasspathTest {
  private fun fixture(block: (File) -> Unit) {
    val root = Files.createTempDirectory("bazel-classpath").toFile()
    try {
      block(root)
    } finally {
      root.deleteRecursively()
    }
  }

  private fun File.jar(name: String, vararg entries: String): File =
    resolve(name).apply {
      ZipOutputStream(outputStream()).use { zip ->
        entries.forEach {
          zip.putNextEntry(ZipEntry(it))
          zip.closeEntry()
        }
      }
    }

  @Test
  fun `empty jars and covered R jars leave, everything else stays in order`() = fixture { root ->
    val rFinal =
      root.jar(
        "r-final.jar",
        "com/example/app/R.class",
        "com/example/app/R\$string.class",
        "androidx/compose/ui/R.class",
        "androidx/compose/ui/R\$string.class",
      )
    val app = root.jar("app.jar", "com/example/app/MainKt.class")
    // A project jar is kept even when it holds nothing.
    val projectStub = root.jar("project-stub.jar", "META-INF/MANIFEST.MF")
    val uiR = root.jar("ui_resources.jar", "androidx/compose/ui/R\$string.class")
    // An R class the final jar lacks: the library's own jar is still the only definition.
    val coreR = root.jar("core_resources.jar", "androidx/core/R\$id.class")
    val kmpStub =
      root.jar(
        "processed_runtime-desktop.jar",
        "META-INF/",
        "META-INF/MANIFEST.MF",
        "META-INF/runtime.kotlin_module",
        "META-INF/maven/org.jetbrains.compose/runtime/pom.xml",
      )
    // Service registrations and plain resources are loaded by name.
    val services = root.jar("services.jar", "META-INF/services/com.example.Plugin")
    val ui = root.jar("ui.jar", "androidx/compose/ui/Modifier.class")
    val unreadable = root.resolve("broken.jar").apply { writeText("not a zip") }

    val pruned =
      pruneRuntimeClasspath(
        listOf(rFinal, app, projectStub, uiR, coreR, kmpStub, services, ui, unreadable),
        keep = setOf(app, projectStub),
        rJar = rFinal,
      )

    assertEquals(listOf(rFinal, app, projectStub, coreR, services, ui, unreadable), pruned.jars)
    assertEquals(listOf(kmpStub), pruned.empty)
    assertEquals(listOf(uiR), pruned.supersededRJars)
  }

  @Test
  fun `many jars scanned at once keep the classpath order`() = fixture { root ->
    val rFinal = root.jar("r-final.jar", "lib0/R.class")
    // More jars than scan threads, interleaving every kind, so ordering can't come from luck.
    val runtime =
      (0 until 200).map { i ->
        when (i % 4) {
          0 -> root.jar("lib$i.jar", "lib$i/Lib.class", "lib$i/R.class")
          1 -> root.jar("empty$i.jar", "META-INF/MANIFEST.MF")
          2 -> root.jar("r$i.jar", "lib0/R.class")
          else -> root.jar("res$i.jar", "lib$i/strings.xml")
        }
      }
    val pruned = pruneRuntimeClasspath(listOf(rFinal) + runtime, keep = emptySet(), rJar = rFinal)
    assertEquals(
      listOf(rFinal) + runtime.filterIndexed { i, _ -> i % 4 == 0 || i % 4 == 3 },
      pruned.jars,
    )
    assertEquals(runtime.filterIndexed { i, _ -> i % 4 == 1 }, pruned.empty)
    assertEquals(runtime.filterIndexed { i, _ -> i % 4 == 2 }, pruned.supersededRJars)
  }

  @Test
  fun `without a final R jar no R jar is dropped`() = fixture { root ->
    val app = root.jar("app.jar", "com/example/App.class")
    val uiR = root.jar("ui_resources.jar", "androidx/compose/ui/R\$string.class")
    val pruned = pruneRuntimeClasspath(listOf(app, uiR), keep = setOf(app), rJar = null)
    assertEquals(listOf(app, uiR), pruned.jars)
  }

  @Test
  fun `resolve keeps the metadata's runtime paths in step with the pruned files`() =
    fixture { root ->
      root.jar("app.jar", "com/example/App.class")
      root.jar("stub.jar", "META-INF/MANIFEST.MF")
      root.jar("lib.jar", "com/example/Lib.class")
      val resolved =
        BazelArtifacts(
            "//:app",
            "bin",
            listOf("app.jar"),
            listOf("app.jar", "stub.jar", "lib.jar", "lib.jar"),
          )
          .resolve(root)
      assertEquals(
        listOf(root.resolve("app.jar"), root.resolve("lib.jar")).map { it.absoluteFile },
        resolved.runtimeJars,
      )
      assertEquals(listOf("app.jar", "lib.jar"), resolved.metadata.runtimeJars)
    }
}
