package ee.schimke.composeai.cli

import ee.schimke.composeai.buildhost.BuildHostCodec
import ee.schimke.composeai.buildhost.BuildHostEnvelope
import ee.schimke.composeai.buildhost.BuildHostProtocol
import ee.schimke.composeai.buildhost.BuildHostRequest
import ee.schimke.composeai.buildhost.BuildHostResponse
import ee.schimke.composeai.discovery.Capture
import ee.schimke.composeai.discovery.PreviewInfo
import ee.schimke.composeai.discovery.PreviewManifest
import java.io.File
import java.io.StringWriter
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The build host in a Bazel workspace, driven over the same protocol the preview server speaks
 * (`browse`, `serve --discover`). The Bazel driver itself is replaced by a fake that writes what
 * the normal surface writes, so these cover the mapping, not a Bazel build (the contrib fixture's
 * CI does that).
 */
class BazelBuildHostTest {
  private fun workspace(block: (File) -> Unit) {
    val root = Files.createTempDirectory("bazel-build-host").toFile()
    try {
      root.resolve("MODULE.bazel").writeText("module(name = \"w\")\n")
      block(root)
    } finally {
      root.deleteRecursively()
    }
  }

  /** What `compose-preview bazel render|prepare` leaves behind for `//app/ui:lib`. */
  private class FakeBazel(private val root: File, var fail: Boolean = false) :
    (List<String>) -> List<PreparedBazelPreviews> {
    val calls = mutableListOf<List<String>>()

    override fun invoke(args: List<String>): List<PreparedBazelPreviews> {
      calls += args
      if (fail) error("Build command exited 1")
      val output =
        root.resolve(".compose-preview/outputs/abc/build/compose-previews").apply { mkdirs() }
      output.resolve("daemon-launch.json").writeText("{}")
      val manifest =
        PreviewManifest(
          "//app/ui:lib",
          "bazel-out/k8-fastbuild/bin",
          listOf(
            PreviewInfo(
              "app.ui.GreetingKt.Greeting",
              "Greeting",
              "app.ui.GreetingKt",
              captures = listOf(Capture(renderOutput = "renders/Greeting.png")),
            )
          ),
        )
      val file =
        output.resolve("previews.json").apply { writeText(bazelJson.encodeToString(manifest)) }
      return listOf(PreparedBazelPreviews(manifest, file))
    }
  }

  private fun exchange(
    host: BuildHostCommand,
    vararg requests: BuildHostRequest,
  ): List<BuildHostEnvelope> {
    val input =
      (listOf(BuildHostRequest.Handshake()) + requests)
        .mapIndexed { i, r -> BuildHostCodec.encode(BuildHostEnvelope(i + 1L, request = r)) }
        .joinToString("\n") + "\n"
    val out = StringWriter()
    host.serve(input.reader().buffered(), out)
    // Events (streamed build output) are not answers; keep the responses in request order.
    return out
      .toString()
      .lines()
      .filter { it.isNotBlank() }
      .map(BuildHostCodec::decode)
      .filter { it.response != null }
  }

  private fun host(root: File, fake: FakeBazel, vararg extra: String): BuildHostCommand {
    val args = listOf(BuildHostProtocol.STDIO_FLAG, "--project", root.path) + extra
    return BuildHostCommand(args).apply { bazel = BazelBuildHost(root.canonicalFile, args, fake) }
  }

  @Test
  fun `a Bazel workspace routes the build host to the Bazel driver, a Gradle one does not`() =
    workspace { root ->
      assertNotNull(
        BuildHostCommand(listOf(BuildHostProtocol.STDIO_FLAG, "--project", root.path)).bazel
      )
      val gradle = Files.createTempDirectory("gradle-build-host").toFile()
      try {
        gradle.resolve("settings.gradle.kts").writeText("")
        assertNull(
          BuildHostCommand(listOf(BuildHostProtocol.STDIO_FLAG, "--project", gradle.path)).bazel
        )
      } finally {
        gradle.deleteRecursively()
      }
    }

  @Test
  fun `discovery reports the target as one module whose projectDir holds the daemon descriptor`() =
    workspace { root ->
      val fake = FakeBazel(root)
      val replies =
        exchange(
          host(root, fake, "--module", "//app/ui:lib"),
          BuildHostRequest.GradleProjectRoot,
          BuildHostRequest.GradleVariantArgs,
          BuildHostRequest.GradleBuildArgs(listOf("--force")),
          BuildHostRequest.DiscoverAndBuild(silenceStdout = true),
          BuildHostRequest.GradleProjects,
        )

      assertIs<BuildHostResponse.Handshake>(replies[0].response)
      // No Gradle root: the server must not walk the workspace's bazel-* symlinks for Wasm apps.
      assertNull(assertIs<BuildHostResponse.Path>(replies[1].response).path)
      assertEquals(emptyList(), assertIs<BuildHostResponse.Strings>(replies[2].response).values)
      assertEquals(emptyList(), assertIs<BuildHostResponse.Strings>(replies[3].response).values)

      val discovery = assertIs<BuildHostResponse.Discovery>(replies[4].response)
      assertTrue(discovery.buildOk)
      val (module, manifest) = discovery.manifests.single()
      assertEquals("app.ui:lib", module.gradlePath)
      val base = File(module.projectDir)
      assertTrue(
        base.resolve(BazelBuildHost.DESCRIPTOR).isFile,
        "server reads $base/${BazelBuildHost.DESCRIPTOR}",
      )
      assertEquals("app.ui.GreetingKt.Greeting", manifest.previews.single().id)
      // The label passed as --module reaches the driver as --target, and the build renders.
      assertEquals("render", fake.calls.single().first())
      assertEquals("//app/ui:lib", fake.calls.single().let { it[it.indexOf("--target") + 1] })

      val modules = assertIs<BuildHostResponse.Modules>(replies[5].response).modules
      assertEquals(listOf(module), modules)
    }

  @Test
  fun `daemon start is satisfied by the descriptor and other Gradle tasks are refused`() =
    workspace { root ->
      val fake = FakeBazel(root)
      val replies =
        exchange(
          host(root, fake),
          BuildHostRequest.DiscoverAndBuild(silenceStdout = true),
          BuildHostRequest.RunGradleTasks(listOf(":app.ui:lib:composePreviewDaemonStart")),
          BuildHostRequest.RunGradleTasks(listOf(":app.ui:lib:assemble")),
        )

      assertTrue(assertIs<BuildHostResponse.BuildResult>(replies[2].response).buildOk)
      // Rendering already wrote the descriptor, so starting the daemon builds nothing more.
      assertEquals(1, fake.calls.size)
      assertFalse(assertIs<BuildHostResponse.BuildResult>(replies[3].response).buildOk)
    }

  @Test
  fun `a missing descriptor is prepared for the module's own label`() = workspace { root ->
    val fake = FakeBazel(root)
    val bazelHost = host(root, fake).bazel!!
    bazelHost.discoverAndBuild()
    // A cleaned output tree between discovery and daemon start.
    root.resolve(".compose-preview/outputs/abc/${BazelBuildHost.DESCRIPTOR}").delete()

    assertTrue(bazelHost.runTasks(listOf(":app.ui:lib:composePreviewDaemonStart")))
    val prepare = fake.calls.last()
    assertEquals("prepare", prepare.first())
    assertEquals("//app/ui:lib", prepare[prepare.indexOf("--target") + 1])
  }

  @Test
  fun `a failed Bazel build is reported as a failed discovery, not a crash`() = workspace { root ->
    val replies =
      exchange(host(root, FakeBazel(root, fail = true)), BuildHostRequest.DiscoverAndBuild(true))
    val discovery = assertIs<BuildHostResponse.Discovery>(replies[1].response)
    assertFalse(discovery.buildOk)
    assertEquals(emptyList(), discovery.manifests)
  }

  @Test
  fun `target selection starts from the directory browse was pointed at, not the workspace root`() {
    assertEquals(
      File("/ws/app/ui"),
      BazelBuildHost.invocationDirectory(listOf("--project", "/ws/app/ui", "--stdio")),
    )
    assertEquals(File("."), BazelBuildHost.invocationDirectory(listOf("--stdio")))
  }

  @Test
  fun `a target pattern reports one module per target and starts each one's daemon`() =
    workspace { root ->
      val calls = mutableListOf<List<String>>()
      val prepare = { label: String, key: String ->
        val output =
          root.resolve(".compose-preview/outputs/$key/build/compose-previews").apply { mkdirs() }
        output.resolve("daemon-launch.json").writeText("{}")
        val manifest =
          PreviewManifest(
            label,
            "bazel-out/k8-fastbuild/bin",
            listOf(
              PreviewInfo("$key.P", "P", key, captures = listOf(Capture(renderOutput = "r.png")))
            ),
          )
        PreparedBazelPreviews(
          manifest,
          output.resolve("previews.json").apply { writeText(bazelJson.encodeToString(manifest)) },
        )
      }
      val args =
        listOf(BuildHostProtocol.STDIO_FLAG, "--project", root.path, "--target", "//app/...")
      val host =
        BazelBuildHost(root.canonicalFile, args) { bazelArgs ->
          calls += bazelArgs
          listOf(prepare("//app/ui:lib", "ui"), prepare("//app/data:lib", "data"))
        }

      val (ok, manifests) = host.discoverAndBuild()

      assertTrue(ok)
      assertEquals(listOf("app.ui:lib", "app.data:lib"), manifests.map { it.module.gradlePath })
      assertEquals("//app/...", calls.single().let { it[it.indexOf("--target") + 1] })
      // Each module's descriptor is already there: starting either builds nothing more.
      assertTrue(host.runTasks(listOf(":app.data:lib:composePreviewDaemonStart")))
      assertEquals(1, calls.size)
    }

  @Test
  fun `labels map to Gradle-shaped module paths without slashes`() {
    assertEquals("app.ui:lib", BazelBuildHost.modulePath("//app/ui:lib"))
    assertEquals("aspect_app", BazelBuildHost.modulePath("//:aspect_app"))
    assertEquals("app:app", BazelBuildHost.modulePath("//app:app"))
  }
}
