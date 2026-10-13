package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.bundle.coordinates.CoordinateResolver
import ee.schimke.composeai.bundle.producer.bazelBundleSha
import ee.schimke.composeai.cli.serve.ServeBundleDaemon
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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
      // The portable polyglot must be independently runnable by the server, not just baked PNGs.
      val bundle = output.resolve("runnable.png")
      BazelCommand(
          listOf(
            "bundle",
            "--project",
            root.path,
            "--artifacts",
            metadata.path,
            "--execution-root",
            executionRoot,
            "--out",
            output.resolve("portable").path,
            "--filter",
            "RuntimeGreeting",
            "--maven-install",
            root.resolve("maven_install.json").path,
            "--output",
            bundle.path,
            "--timeout",
            "60",
          )
        )
        .execute()
      val reused = output.resolve("reused.png")
      BazelCommand(
          listOf(
            "render",
            "--reuse-renders",
            "--project",
            root.path,
            "--artifacts",
            metadata.path,
            "--execution-root",
            executionRoot,
            "--out",
            output.resolve("portable").path,
            "--filter",
            "RuntimeGreeting",
            "--output",
            reused.path,
          )
        )
        .execute()
      assertEquals(bazelBundleSha(png), bazelBundleSha(reused))
      val packed = BundleReader.readMetadata(bundle).manifest
      assertEquals("bazel", packed.producer)
      val coordinates = packed.classpath.filterIsInstance<BundleReader.ClasspathEntry.Maven>()
      assertTrue(
        coordinates.any {
          it.group.startsWith("org.jetbrains.compose") && it.artifact == "ui-desktop"
        }
      )
      assertTrue(coordinates.any { it.artifact == "runtime-desktop" })
      assertTrue(
        coordinates
          .filter { it.group.startsWith("org.jetbrains.compose") }
          .all { it.sha256?.length == 64 }
      )
      val suppliedMaven = System.getenv("COMPOSE_PREVIEW_BAZEL_MAVEN_REPO")?.let(::File)
      val resolved =
        CoordinateResolver(
            repositoryRoots =
              suppliedMaven?.let { listOf(it) } ?: CoordinateResolver.defaultRepositoryRoots(),
            networkEnabled = suppliedMaven == null,
          )
          .resolveAll(coordinates)
      assertTrue(
        resolved.all {
          it.file != null && !it.mismatch && (it.coordinate.sha256 == null || it.verified)
        },
        "Every advertised coordinate must resolve to exact pinned bytes: $resolved",
      )
      // Force an actual Central download into an empty cache, independent of Bazel/Gradle outputs.
      val central = coordinates.first {
        it.group == "org.jetbrains.compose.ui" && it.artifact == "ui-desktop"
      }
      val fetched =
        CoordinateResolver(
            repositoryRoots = suppliedMaven?.let { listOf(it) } ?: emptyList(),
            downloadCacheDir = output.resolve("fresh-maven"),
            remoteRepositories = listOf("https://repo.maven.apache.org/maven2"),
            networkEnabled = suppliedMaven == null,
          )
          .resolve(central)
      assertTrue(
        fetched.file != null &&
          fetched.verified &&
          (suppliedMaven != null || fetched.downloaded) &&
          !fetched.mismatch,
        "Compose UI must resolve from Maven Central with the bundle checksum: $fetched",
      )
      val oldMavenRepo = System.getProperty("maven.repo.local")
      if (suppliedMaven != null) System.setProperty("maven.repo.local", suppliedMaven.path)
      val server =
        try {
          requireNotNull(
            ServeBundleDaemon.materialize(bundle, output.resolve("server"), "bazel-bundle")
          )
        } finally {
          if (oldMavenRepo == null) System.clearProperty("maven.repo.local")
          else System.setProperty("maven.repo.local", oldMavenRepo)
        }
      // The managed test host has a read-only default home; Skiko extracts natives here.
      val home = output.resolve("server-home").apply { mkdirs() }
      val launch =
        bazelJson.parseToJsonElement(server.descriptor.readText())
          as kotlinx.serialization.json.JsonObject
      val properties =
        (launch["systemProperties"] as kotlinx.serialization.json.JsonObject) +
          ("user.home" to kotlinx.serialization.json.JsonPrimitive(home.path))
      server.descriptor.writeText(
        kotlinx.serialization.json
          .JsonObject(
            launch + ("systemProperties" to kotlinx.serialization.json.JsonObject(properties))
          )
          .toString()
      )
      val serverPng = AtomicReference<File?>()
      val serverFinished = CountDownLatch(1)
      SubprocessRenderSessions.open(
          RenderSessionConfig(
            descriptorPath = server.descriptor,
            workspaceRoot = server.workspaceRoot,
            initializeTimeout = 60.seconds,
            maxRenderTime = 60.seconds,
          )
        )
        .use { session ->
          session
            .onNotification { method, params ->
              if (
                method in setOf("renderFinished", "renderFailed") &&
                  params?.get("id")?.jsonPrimitive?.contentOrNull == greeting.id
              ) {
                serverPng.set(params["pngPath"]?.jsonPrimitive?.contentOrNull?.let(::File))
                serverFinished.countDown()
              }
            }
            .use {
              assertTrue(
                session.renderNow(listOf(greeting.id), timeout = 60.seconds).rejected.isEmpty()
              )
              assertTrue(
                serverFinished.await(60, TimeUnit.SECONDS),
                "Server must re-render the portable bundle",
              )
            }
        }
      assertTrue(requireNotNull(ImageIO.read(requireNotNull(serverPng.get()))).width > 1)
      System.getenv("COMPOSE_PREVIEW_BAZEL_BUNDLE_EVIDENCE")?.let { path ->
        File(path).apply {
          parentFile.mkdirs()
          bundle.copyTo(this, overwrite = true)
        }
      }
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
