package ee.schimke.composeai.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/** Dedicated CI job supplies the real contrib fixture; ordinary unit tests don't need Bazel. */
@EnabledIfEnvironmentVariable(named = "COMPOSE_PREVIEW_BAZEL_FIXTURE", matches = ".+")
class BazelComposeIntegrationTest {
  @Test
  fun `aspect discovers generated application previews and renders runtime-only code and resources`() {
    val root = File(requireNotNull(System.getenv("COMPOSE_PREVIEW_BAZEL_FIXTURE"))).canonicalFile
    installBazelAspect(root)
    val config =
      BazelBuildConfig(
        buildCommand = listOf(System.getenv("COMPOSE_PREVIEW_BAZEL_COMMAND") ?: "bazel"),
        startupArgs =
          System.getenv("COMPOSE_PREVIEW_BAZEL_OUTPUT_ROOT")
            ?.let { listOf("--output_user_root=$it") }
            .orEmpty(),
        buildArgs = listOf("--remote_download_outputs=all", "--jobs=4"),
      )
    val artifacts = BazelBuild().collect(root, "//:aspect_app", config, 300)
    assertTrue(artifacts.runtimeJars.any { it.name.contains("runtime_message") })
    assertTrue(artifacts.projectJars.none { it.name.contains("runtime_message") })
    val runtimeMessage = artifacts.runtimeJars.first { it.name.contains("runtime_message") }
    ZipFile(runtimeMessage).use { assertTrue(it.getEntry("runtime-message.txt") != null) }
    assertTrue(artifacts.sourceFiles.any { it.name == "GeneratedGreeting.kt" && it.isFile })
    val output = Files.createTempDirectory("bazel-compose-render").toFile()
    try {
      val prepared = prepareBazelPreviews(artifacts, root, output)
      assertEquals(
        setOf("RuntimeGreeting", "GeneratedGreeting"),
        prepared.manifest.previews.map { it.functionName }.toSet(),
      )
      val greeting = prepared.manifest.previews.single { it.functionName == "RuntimeGreeting" }
      assertTrue(greeting.sourceFile?.endsWith("RuntimeGreeting.kt") == true)
      // Exercise the actual CLI rendering path, including metadata import, daemon provisioning,
      // initialize/renderFinished lifecycle, selection and --output.
      val metadata =
        output.resolve("artifacts.json").apply {
          writeText(bazelJson.encodeToString(artifacts.metadata))
        }
      val executionRoot =
        BazelProcess.run(
            config.buildCommand + config.startupArgs + listOf("info", "execution_root"),
            root,
            60,
          )
          .trim()
      val png = output.resolve("greeting.png")
      BazelCommand(
          listOf(
            "render",
            "--project",
            root.absolutePath,
            "--artifacts",
            metadata.absolutePath,
            "--execution-root",
            executionRoot,
            "--out",
            output.absolutePath,
            "--id",
            greeting.id,
            "--output",
            png.absolutePath,
            "--timeout",
            "60",
          )
        )
        .execute()
      val image = requireNotNull(ImageIO.read(png))
      assertTrue(image.width > 1 && image.height > 1)
      var darkPixels = 0
      var lightPixels = 0
      for (y in 0 until image.height) for (x in 0 until image.width) {
        val argb = image.getRGB(x, y)
        if ((argb ushr 24) > 200) {
          val brightness = ((argb shr 16) and 255) + ((argb shr 8) and 255) + (argb and 255)
          if (brightness < 300) darkPixels++
          if (brightness > 700) lightPixels++
        }
      }
      assertTrue(
        darkPixels > 25 && lightPixels > 1000,
        "Compose UI must contain visible dark text on an opaque light background",
      )
      // The normal commands detect Bazel, use the shared repo target/config and retain
      // the existing CLI JSON envelope. No --target or explicit bazel verb is needed.
      val configFile = root.resolve(".compose-preview/bazel.json")
      val previousConfig = configFile.takeIf { it.isFile }?.readText()
      configFile.writeText(bazelJson.encodeToString(config.copy(target = "//:aspect_app")))
      fun capture(command: String, extra: List<String> = emptyList()): PreviewListResponse {
        val buffer = ByteArrayOutputStream()
        val original = System.out
        try {
          System.setOut(PrintStream(buffer))
          runProjectPreviewCommand(
            command,
            listOf(
              "--project",
              root.path,
              "--json",
              "--out",
              output.resolve("normal").path,
              "--timeout",
              "60",
            ) + extra,
          )
        } finally {
          System.setOut(original)
        }
        return bazelJson.decodeFromString(buffer.toString(Charsets.UTF_8))
      }
      try {
        assertEquals(
          ProjectBuildKind.BAZEL,
          previewBuildRoute(listOf("--project", root.path))?.kind,
        )
        val listed = capture("list")
        assertEquals(SHOW_LIST_SCHEMA, listed.schema)
        assertEquals(
          setOf("RuntimeGreeting", "GeneratedGreeting"),
          listed.previews.map { it.functionName }.toSet(),
        )
        val shown = capture("show", listOf("--preview", "RuntimeGreeting"))
        assertEquals(1, shown.previews.size)
        assertTrue(File(requireNotNull(shown.previews.single().pngPath)).isFile)
        assertTrue(shown.previews.single().sha256?.isNotBlank() == true)
        assertEquals(1, shown.counts?.total)
      } finally {
        if (previousConfig == null) configFile.delete() else configFile.writeText(previousConfig)
      }
      val evidence = System.getenv("COMPOSE_PREVIEW_BAZEL_EVIDENCE")?.let(::File)
      if (evidence != null) {
        evidence.parentFile.mkdirs()
        png.copyTo(evidence, overwrite = true)
      }
    } finally {
      output.deleteRecursively()
    }
  }
}
