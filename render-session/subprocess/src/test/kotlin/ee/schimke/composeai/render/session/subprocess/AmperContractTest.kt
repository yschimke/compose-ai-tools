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
 * End-to-end proof that an Amper-built module's `@Preview` can be driven through `RenderSession`
 * without any Gradle involvement in producing the user classes.
 *
 * The fixture at `samples/amper-cmp-desktop/` is compiled by `./amper build` — a non-Gradle build
 * system. This test:
 *
 * 1. Reuses the renderer/connector/data jars and the Compose Desktop runtime jar bag from the
 *    gradle plugin's reference descriptor (`:samples:cmp:composePreviewDaemonStart`). A real Amper
 *    consumer would resolve these from Maven Central; `NonGradleContractTest` already proves that
 *    descriptor synthesis is independent of the plugin, so this test focuses on the user-classes
 *    swap rather than re-proving classpath synthesis.
 * 2. Filters cmp's compiled-class directories out of the inherited classpath and substitutes
 *    Amper's `kotlin-output/` (the directory `./amper build` writes `GreetingKt.class` to).
 * 3. Hand-authors `previews.json` with one entry for `GreetingKt.Greeting`. Preview discovery is
 *    still gradle-coupled (`DiscoverPreviewsTask` runs inside a `@TaskAction`); extracting the
 *    ClassGraph scan into a library is a separate follow-up tracked in
 *    `docs/NON_GRADLE_INTEGRATION.md`.
 * 4. Opens `SubprocessRenderSessions` against the synthesised descriptor, drives `renderNow`, waits
 *    for a `renderFinished` notification, asserts the PNG lands on disk.
 *
 * **Self-skip:** the test exits cleanly (no failure) when the prerequisite inputs are missing.
 *
 * * Amper wrapper missing? Vendored as `samples/amper-cmp-desktop/amper`.
 * * Amper outputs missing? Run `./amper build` from the fixture first. In sandbox environments with
 *   a TLS-inspection proxy the bundled Zulu JRE needs the system truststore wired via
 *   `AMPER_JAVA_OPTIONS="-ea -XX:+EnableDynamicAgentLoading
 *   -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts
 *   -Djavax.net.ssl.trustStorePassword=changeit"`.
 * * Gradle reference descriptor missing? Built by the `:samples:cmp:composePreviewDaemonStart`
 *   dependency declared in this module's `build.gradle.kts`.
 */
class AmperContractTest {

  @get:Rule val tempDir: TemporaryFolder = TemporaryFolder()

  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
  }

  @Test
  fun `amper-built classes drive a real render via RenderSession`() {
    val repoRoot = locateRepoRoot()
    val amperFixtureDir = File(repoRoot, "samples/amper-cmp-desktop")
    val amperKotlinOutput =
      File(
        amperFixtureDir,
        "build/artifacts/CompiledJvmArtifact/amper-cmp-desktopjvm/kotlin-output",
      )
    val gradleRefDescriptor =
      File(repoRoot, "samples/cmp/build/compose-previews/daemon-launch.json")

    if (!File(amperFixtureDir, "amper").canExecute()) {
      System.err.println(
        "[AmperContractTest] skipping — `samples/amper-cmp-desktop/amper` wrapper missing or not executable"
      )
      return
    }
    val classFiles = amperKotlinOutput.listFiles { f -> f.extension == "class" }
    if (classFiles == null || classFiles.isEmpty()) {
      System.err.println(
        "[AmperContractTest] skipping — Amper outputs missing at $amperKotlinOutput. " +
          "Run `cd samples/amper-cmp-desktop && AMPER_JAVA_OPTIONS=\"…trustStore=…\" ./amper build` first."
      )
      return
    }
    if (!gradleRefDescriptor.isFile) {
      System.err.println(
        "[AmperContractTest] skipping — gradle reference descriptor missing at $gradleRefDescriptor"
      )
      return
    }

    // 1. The gradle descriptor is our "Maven-Central-resolved bag" for renderer + Compose Desktop
    //    jars. We don't re-derive the classpath from scratch — that recipe is the subject of
    //    NonGradleContractTest. Here the focus is on the user-classes substitution.
    val referenceDescriptor = json.parseToJsonElement(gradleRefDescriptor.readText()).jsonObject
    val refClasspath = referenceDescriptor["classpath"]!!.jsonArray.map { it.jsonPrimitive.content }
    val jvmArgs = referenceDescriptor["jvmArgs"]!!.jsonArray.map { it.jsonPrimitive.content }
    val mainClass = referenceDescriptor["mainClass"]!!.jsonPrimitive.content

    // Strip cmp's compiled-class dirs (the only user-classes entries in the reference descriptor)
    // and append Amper's kotlin-output dir in their place. The renderer + connector + data jars
    // stay; the Compose Desktop runtime stays.
    val cmpUserDirsPrefix = File(repoRoot, "samples/cmp/build/classes/").absolutePath
    val classpath =
      refClasspath.filter { !it.startsWith(cmpUserDirsPrefix) } + amperKotlinOutput.absolutePath

    // 2. Synthesise a fresh descriptor in a tmp dir. Render outputs land in tmp so the test
    //    doesn't touch the on-disk gradle build outputs.
    val moduleDir = tempDir.newFolder("amper-module")
    val renderOutputDir = File(moduleDir, "build/compose-previews/renders").apply { mkdirs() }
    val historyDir = File(moduleDir, ".compose-preview-history").apply { mkdirs() }
    val previewsJsonPath = File(moduleDir, "build/compose-previews/previews.json")
    previewsJsonPath.parentFile.mkdirs()
    previewsJsonPath.writeText(handAuthoredPreviewsJson(modulePath = ":amper-cmp-desktop"))

    val descriptorFile = File(moduleDir, "build/compose-previews/daemon-launch.json")
    descriptorFile.writeText(
      writeDescriptor(
          modulePath = ":amper-cmp-desktop",
          variant = "desktop",
          mainClass = mainClass,
          classpath = classpath,
          jvmArgs = jvmArgs,
          moduleDir = moduleDir,
          workspaceRoot = repoRoot,
          previewsJsonPath = previewsJsonPath,
          renderOutputDir = renderOutputDir,
          historyDir = historyDir,
          userClassDir = amperKotlinOutput,
        )
        .toString()
    )

    val target = "GreetingKt.Greeting"

    SubprocessRenderSessions.open(
        RenderSessionConfig(
          descriptorPath = descriptorFile,
          workspaceRoot = repoRoot,
          workspaceName = "compose-ai-tools",
          logSink = { line -> System.err.println("[amper-daemon] $line") },
        )
      )
      .use { session ->
        assertThat(session.modulePath).isEqualTo(":amper-cmp-desktop")
        assertThat(session.initializeResult.daemonVersion).isNotEmpty()

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
            assertWithMessage("renderNow should accept Greeting, got rejected=${queueAck.rejected}")
              .that(queueAck.rejected.map { it.id })
              .doesNotContain(target)

            assertWithMessage("daemon should emit renderFinished for $target within 60s")
              .that(latch.await(60, TimeUnit.SECONDS))
              .isTrue()
          }

        val params = finishedParams.get() ?: error("renderFinished notification missing payload")
        val pngPath =
          params["pngPath"]?.jsonPrimitive?.contentOrNull
            ?: error("renderFinished params missing pngPath: $params")
        val png = File(pngPath)
        assertWithMessage("rendered PNG must exist on disk: $pngPath").that(png.isFile).isTrue()
        assertThat(png.length()).isGreaterThan(0L)
      }
  }

  /**
   * Hand-written `previews.json` for `Greeting()`. Mirrors the schema in `PreviewData.kt`
   * (`PreviewManifest` → `PreviewInfo` → `Capture`). A real non-Gradle integration would emit this
   * by running ClassGraph over the user classes; for one preview, hand-authoring is cheaper than
   * pulling in a discovery library.
   */
  private fun handAuthoredPreviewsJson(modulePath: String): String =
    json.encodeToString(
      JsonObject.serializer(),
      buildJsonObject {
        put("module", JsonPrimitive(modulePath))
        put("variant", JsonPrimitive("desktop"))
        put(
          "previews",
          JsonArray(
            listOf(
              buildJsonObject {
                put("id", JsonPrimitive("GreetingKt.Greeting"))
                put("functionName", JsonPrimitive("Greeting"))
                put("className", JsonPrimitive("GreetingKt"))
                put("sourceFile", JsonPrimitive("src/Greeting.kt"))
                put(
                  "params",
                  buildJsonObject {
                    put("density", JsonPrimitive(2.625f))
                    put("fontScale", JsonPrimitive(1.0f))
                    put("showSystemUi", JsonPrimitive(false))
                    put("showBackground", JsonPrimitive(true))
                    put("backgroundColor", JsonPrimitive(0xFFFFFFFFL))
                    put("uiMode", JsonPrimitive(0))
                    put("previewParameterLimit", JsonPrimitive(Int.MAX_VALUE))
                    put("kind", JsonPrimitive("COMPOSE"))
                  },
                )
                put(
                  "captures",
                  JsonArray(
                    listOf(
                      buildJsonObject {
                        put("renderOutput", JsonPrimitive("renders/GreetingKt.Greeting.png"))
                        put("cost", JsonPrimitive(1.0f))
                      }
                    )
                  ),
                )
                put("dataProducts", JsonArray(emptyList()))
                put("targets", JsonArray(emptyList()))
              }
            )
          ),
        )
      },
    )

  /**
   * Hand-written `daemon-launch.json` builder. Same field-by-field shape `NonGradleContractTest`
   * uses — this is the bare contract a non-Gradle producer emits.
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
    userClassDir: File,
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
        put("composeai.daemon.userClassDirs", JsonPrimitive(userClassDir.absolutePath))
        put("composeai.daemon.idleTimeoutMs", JsonPrimitive("5000"))
      },
    )
    put("workingDirectory", JsonPrimitive(moduleDir.absolutePath))
    put("manifestPath", JsonPrimitive(previewsJsonPath.absolutePath))
  }

  private fun locateRepoRoot(): File {
    var dir: File? = File(".").canonicalFile
    while (dir != null) {
      if (File(dir, "settings.gradle.kts").isFile) return dir
      dir = dir.parentFile
    }
    error("Could not locate repo root above ${File(".").canonicalFile}")
  }
}
