package ee.schimke.composeai.render.session.subprocess

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ee.schimke.composeai.daemon.protocol.RenderTier
import ee.schimke.composeai.render.session.RenderSessionConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Proves the `daemon-launch.json` schema is a stable contract independent of the Gradle plugin: any
 * non-Gradle build (Amper, Bazel, Buck2, a shell script) that can resolve a runtime classpath
 * + locate compiled `@Preview` classes can author a working descriptor by hand and drive a
 *   `RenderSession` against it.
 *
 * The test does **not** call `:samples:cmp:composePreviewDaemonStart`. It does call
 * `:samples:cmp:composePreviewDiscover` (via the gradle pre-build) so a `previews.json` exists on
 * disk — preview discovery is one of the two contracts a non-Gradle integration owns, but the
 * discovery library extraction is a separate piece of work (see `docs/NON_GRADLE_INTEGRATION.md` §
 * "Limitations and follow-ups"); for now the test reuses an existing manifest the way an Amper /
 * Bazel rule would in a v1 integration.
 *
 * **What's proven:**
 *
 * 1. The classpath / mainClass / sysprops the daemon needs at boot can be assembled outside the
 *    plugin (the test rebuilds the descriptor field-by-field from a parsed reference, not from a
 *    Gradle action).
 * 2. `SubprocessRenderSessions.open(...)` accepts the synthesised descriptor and completes the
 *    `initialize` handshake.
 * 3. `renderNow` produces a real PNG against the synthesised classpath — i.e. the daemon picks up
 *    the user classes and Compose runtime from the descriptor, not from any ambient gradle state.
 *
 * **Self-skip:** the test skips cleanly when the prerequisite gradle outputs are missing — devs who
 * haven't run `:samples:cmp:composePreviewDiscover` shouldn't see a hard failure. CI runs the
 * pre-step explicitly via `render-session/subprocess/build.gradle.kts`.
 */
class NonGradleContractTest {

  @get:Rule val tempDir: TemporaryFolder = TemporaryFolder()

  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
  }

  @Test
  fun `synthesised descriptor drives a real render via RenderSession`() {
    val repoRoot = locateRepoRoot()
    val sampleProjectDir = File(repoRoot, "samples/cmp")
    val previewsJson = File(sampleProjectDir, "build/compose-previews/previews.json")
    val gradlePluginDescriptor = File(sampleProjectDir, "build/compose-previews/daemon-launch.json")

    if (!previewsJson.isFile || !gradlePluginDescriptor.isFile) {
      System.err.println(
        "[NonGradleContractTest] skipping — run `:samples:cmp:composePreviewDiscover " +
          ":samples:cmp:composePreviewDaemonStart` first to produce the reference inputs."
      )
      return
    }

    // 1. Treat the gradle-plugin's descriptor as a *parts list* — classpath entries, sysprop
    //    values, JVM args — that any other producer would also need to surface. The synthesis
    //    below uses individual fields, not the file as a whole; a real Amper / Bazel integration
    //    would resolve the same parts from its own dep graph.
    val referenceDescriptor = json.parseToJsonElement(gradlePluginDescriptor.readText()).jsonObject
    val classpath = referenceDescriptor.classpathList()
    val jvmArgs = referenceDescriptor["jvmArgs"]!!.jsonArray.map { it.jsonPrimitive.content }
    val mainClass = referenceDescriptor["mainClass"]!!.jsonPrimitive.content

    // 2. Synthesise a *fresh* descriptor in a tmp dir — render outputs land here too, so the test
    //    doesn't trample the on-disk gradle outputs. This is what makes the test independent of
    //    the gradle plugin: the descriptor we open against is one we wrote field-by-field.
    val moduleDir = tempDir.newFolder("module")
    val renderOutputDir = File(moduleDir, "build/compose-previews/renders").apply { mkdirs() }
    val historyDir = File(moduleDir, ".compose-preview-history").apply { mkdirs() }
    val previewsJsonCopy = File(moduleDir, "build/compose-previews/previews.json")
    previewsJsonCopy.parentFile.mkdirs()
    previewsJsonCopy.writeText(rewritePreviewsForTmp(previewsJson.readText(), renderOutputDir))

    val descriptorFile = File(moduleDir, "build/compose-previews/daemon-launch.json")
    descriptorFile.writeText(
      writeDescriptor(
          modulePath = ":non-gradle-fixture",
          variant = "desktop",
          mainClass = mainClass,
          classpath = classpath,
          jvmArgs = jvmArgs,
          moduleDir = moduleDir,
          workspaceRoot = repoRoot,
          previewsJsonPath = previewsJsonCopy,
          renderOutputDir = renderOutputDir,
          historyDir = historyDir,
        )
        .toString()
    )

    // 3. Open the session against the synthesised descriptor and render a vanilla preview from
    //    the manifest. The handshake must succeed; the render must land a PNG on disk.
    //    `@PreviewParameter`-driven previews are skipped — they need provider expansion the
    //    standalone discovery layer doesn't do here (see `docs/NON_GRADLE_INTEGRATION.md`
    //    "Limitations and follow-ups"); the test would fail loading the composable by raw
    //    method name. A vanilla `@Preview` exercises the same render path.
    val target =
      pickVanillaPreviewId(previewsJsonCopy)
        ?: error("previews.json must contain at least one non-parameterised preview")

    SubprocessRenderSessions.open(
        RenderSessionConfig(
          descriptorPath = descriptorFile,
          workspaceRoot = repoRoot,
          workspaceName = "compose-ai-tools",
          // Surface daemon stderr in the test report so a render failure is debuggable without
          // re-running with extra flags.
          logSink = { line -> System.err.println("[daemon] $line") },
        )
      )
      .use { session ->
        // The synthesised descriptor's modulePath round-trips through the daemon — proves the
        // session is bound to the descriptor we wrote, not to any ambient state.
        assertThat(session.modulePath).isEqualTo(":non-gradle-fixture")
        assertThat(session.initializeResult.daemonVersion).isNotEmpty()

        // `renderNow` is async: the response acknowledges the queue, the PNG arrives later via a
        // `renderFinished` notification. Register a listener *before* calling renderNow so we
        // don't race the daemon dispatching the notification.
        val finishedParams = AtomicReference<JsonObject?>(null)
        val latch = CountDownLatch(1)
        session
          .onNotification { method, params ->
            if (method == "renderFinished" && params != null) {
              val id = params["id"]?.jsonPrimitive?.contentOrNull
              if (id == target) {
                finishedParams.set(params)
                latch.countDown()
              }
            }
          }
          .use {
            val queueAck = session.renderNow(previewIds = listOf(target), tier = RenderTier.FULL)
            assertWithMessage(
                "renderNow should queue our target id, got rejected=${queueAck.rejected}"
              )
              .that(queueAck.rejected.map { it.id })
              .doesNotContain(target)

            // 60s is generous — desktop cold start is ~2s; the rest is render time. Failing here
            // means the daemon didn't dispatch renderFinished, which is almost always a render-side
            // crash captured in the stderr logSink above.
            assertWithMessage("daemon should emit renderFinished for $target within 60s")
              .that(latch.await(60, TimeUnit.SECONDS))
              .isTrue()
          }

        val params =
          finishedParams.get() ?: error("renderFinished notification missing pngPath payload")
        val pngPath =
          params["pngPath"]?.jsonPrimitive?.contentOrNull
            ?: error("renderFinished params missing pngPath: $params")
        val png = File(pngPath)
        assertWithMessage("rendered PNG must exist on disk: $pngPath").that(png.isFile).isTrue()
        assertThat(png.length()).isGreaterThan(0L)
      }
  }

  /**
   * Rewrites `renderOutput` paths in a copied `previews.json` so each preview's PNG lands in our
   * tmp render-output dir (configured via the descriptor's `composeai.render.outputDir` sysprop)
   * rather than the original gradle-plugin output dir. Mirrors what a non-Gradle integration would
   * do post-discovery to point captures at a build-system-controlled output location.
   */
  private fun rewritePreviewsForTmp(rawJson: String, renderOutputDir: File): String {
    val root = json.parseToJsonElement(rawJson).jsonObject
    val previews = root["previews"]!!.jsonArray
    val rewritten = previews.map { previewElement ->
      val preview = previewElement.jsonObject
      val captures =
        preview["captures"]?.jsonArray?.map { captureElement ->
          val capture = captureElement.jsonObject
          // The renderer treats `renderOutput` as relative-to-outputDir; normalising the
          // path to the leaf filename gives the daemon a clean "renders/<file>.png" to write
          // under `composeai.render.outputDir`.
          val originalOutput = capture["renderOutput"]?.jsonPrimitive?.contentOrNull
          val leaf =
            originalOutput?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
              ?: "${preview["id"]!!.jsonPrimitive.content}.png"
          JsonObject(capture + ("renderOutput" to JsonPrimitive("renders/$leaf")))
        }
      JsonObject(
        preview +
          ("captures" to JsonArray(captures ?: emptyList())) +
          ("module" to JsonPrimitive(":non-gradle-fixture"))
      )
    }
    return json.encodeToString(
      JsonObject.serializer(),
      JsonObject(root + ("previews" to JsonArray(rewritten))),
    )
  }

  private fun JsonObject.classpathList(): List<String> =
    this["classpath"]!!.jsonArray.map { it.jsonPrimitive.content }

  /**
   * Returns the first preview id in [previewsJson] whose `params.previewParameterProviderClassName`
   * is `null` — i.e. a vanilla `@Preview` without a `@PreviewParameter`-driven fan-out. The
   * subprocess daemon's `RenderEngine` resolves composable methods by raw name; parameterised
   * previews need provider-instance threading that the daemon's standalone path doesn't do.
   */
  private fun pickVanillaPreviewId(previewsJson: File): String? {
    val parsed = json.parseToJsonElement(previewsJson.readText()).jsonObject
    return parsed["previews"]!!
      .jsonArray
      .firstOrNull { previewElement ->
        val preview = previewElement.jsonObject
        val providerName =
          preview["params"]?.jsonObject?.get("previewParameterProviderClassName")?.jsonPrimitive
        providerName == null || providerName.contentOrNull == null
      }
      ?.jsonObject
      ?.get("id")
      ?.jsonPrimitive
      ?.content
  }

  /**
   * Hand-written `daemon-launch.json` builder. This is the *exact* shape the design document
   * specifies — anything that can produce this JSON works with the subprocess factory. Kept
   * structural rather than typed (no `@Serializable` class) so the test demonstrates the bare
   * contract a non-Kotlin caller (a shell script, a Bazel rule generator) would also produce.
   */
  private fun writeDescriptor(
    modulePath: String,
    variant: String,
    mainClass: String,
    classpath: List<String>,
    jvmArgs: List<String>,
    moduleDir: File,
    workspaceRoot: File,
    previewsJsonPath: File,
    renderOutputDir: File,
    historyDir: File,
  ): JsonObject = buildJsonObject {
    put("schemaVersion", JsonPrimitive(1))
    put("modulePath", JsonPrimitive(modulePath))
    put("variant", JsonPrimitive(variant))
    put("enabled", JsonPrimitive(true))
    put("mainClass", JsonPrimitive(mainClass))
    put("javaLauncher", JsonPrimitive(null as String?))
    put("classpath", JsonArray(classpath.map { JsonPrimitive(it) }))
    put("jvmArgs", JsonArray(jvmArgs.map { JsonPrimitive(it) }))
    put(
      "systemProperties",
      buildJsonObject {
        put("composeai.daemon.protocolVersion", JsonPrimitive("1"))
        put("composeai.daemon.modulePath", JsonPrimitive(modulePath))
        put("composeai.daemon.moduleId", JsonPrimitive(modulePath))
        put("composeai.daemon.moduleProjectDir", JsonPrimitive(moduleDir.absolutePath))
        put("composeai.daemon.workspaceRoot", JsonPrimitive(workspaceRoot.absolutePath))
        put("composeai.daemon.previewsJsonPath", JsonPrimitive(previewsJsonPath.absolutePath))
        put("composeai.harness.previewsManifest", JsonPrimitive(previewsJsonPath.absolutePath))
        put("composeai.render.outputDir", JsonPrimitive(renderOutputDir.absolutePath))
        put("composeai.daemon.historyDir", JsonPrimitive(historyDir.absolutePath))
        put("composeai.daemon.idleTimeoutMs", JsonPrimitive("5000"))
      },
    )
    put("workingDirectory", JsonPrimitive(moduleDir.absolutePath))
    put("manifestPath", JsonPrimitive(previewsJsonPath.absolutePath))
  }

  /**
   * Walks up from the test JVM's working dir until we find the repo's `settings.gradle.kts`. The
   * test classpath plants the working dir somewhere under `render-session/subprocess/` during
   * gradle runs, but every path we feed into the descriptor has to be absolute.
   */
  private fun locateRepoRoot(): File {
    var dir: File? = File(".").canonicalFile
    while (dir != null) {
      if (File(dir, "settings.gradle.kts").isFile) return dir
      dir = dir.parentFile
    }
    error("Could not locate repo root above ${File(".").canonicalFile}")
  }
}
