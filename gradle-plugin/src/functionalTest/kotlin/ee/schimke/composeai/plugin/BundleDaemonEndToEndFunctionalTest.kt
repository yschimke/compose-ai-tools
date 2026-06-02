package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end coverage for `compose-preview bundle daemon` driven through the actual CLI binary.
 *
 * Boots the desktop daemon JVM against a synthetic packed bundle, runs the `initialize` +
 * `shutdown`
 * + `exit` handshake over stdio, and asserts the JVM exits cleanly. Catches the bugs the unit
 *   coverage can't:
 *
 * - `lib-daemon-desktop/` sidecar is populated by `:cli:installDist` (regression: ships empty,
 *   daemon spawn dies with `ClassNotFoundException`).
 * - `lib-renderer/` jars are reachable from the daemon classpath (Compose / Skiko not loaded →
 *   `ImageComposeScene` blows up at first render).
 * - `composeai.daemon.userClassDirs` / `composeai.daemon.previewsJsonPath` sysprop names match what
 *   `DaemonMain` reads on the JVM side.
 * - Daemon's stdio JSON-RPC speaks the same v1 framing the VS Code extension's `DaemonClient`
 *   writes, so a real bundle viewer panel will actually round-trip `initialize`.
 *
 * Reuses the same opt-in + gating as [BundleRenderEndToEndFunctionalTest] —
 * `bundle.render.e2e=true` keys both. The root build's `functionalTestWithBundleRender` task flips
 * it on.
 */
class BundleDaemonEndToEndFunctionalTest {

  @get:Rule val tempDir: TemporaryFolder = TemporaryFolder()

  private val bundleRenderE2E: Boolean =
    System.getProperty("composeai.functionalTest.cliBundleRender", "false") == "true"

  private val cliBinary: String = System.getProperty("composeai.functionalTest.cliBinary", "")

  @Test
  fun `compose-preview bundle daemon serves initialize on stdio and exits cleanly`() {
    assumeTrue("Skipping: -Pbundle.render.e2e=true not set", bundleRenderE2E)

    val cli = locateCli()
    val bundle = packBundle(cli)

    val builder =
      ProcessBuilder(cli.absolutePath, "bundle", "daemon", bundle.absolutePath, "--verbose")
        .directory(tempDir.root)
    // Capture stderr to a file so the assertion message can quote daemon log lines if the
    // handshake hangs — the daemon's [+Nms] boot timeline is useful triage.
    val stderrFile = File(tempDir.root, "daemon-stderr.log")
    builder.redirectError(ProcessBuilder.Redirect.to(stderrFile))
    val proc = builder.start()

    val stdin: OutputStream = proc.outputStream
    val stdout: InputStream = BufferedInputStream(proc.inputStream)
    val json = Json { ignoreUnknownKeys = true }

    var initOk = false
    var shutdownOk = false
    try {
      // 1. initialize request → expect a result with `capabilities`.
      val initParams = buildJsonObject {
        put("clientVersion", "bundle-daemon-e2e/1")
        put("workspaceRoot", "")
        put("moduleId", "bundle:test")
        put("moduleProjectDir", "")
        // PROTOCOL_VERSION is an integer per `daemonProtocol.ts`. The daemon's
        // kotlinx-serialization decoder is strict — passing a string here surfaces as
        // `invalid initialize params: Failed to parse literal "..." as an int`.
        put("protocolVersion", 2)
        put(
          "capabilities",
          buildJsonObject {
            put("visibility", true)
            put("metrics", true)
          },
        )
      }
      BundleDaemonStdio.writeFrame(
        stdin,
        BundleDaemonStdio.jsonRpcRequest(id = 1, method = "initialize", params = initParams),
      )
      val initResponse = BundleDaemonStdio.readFrameWithTimeoutMs(stdout, timeoutMs = 60_000)
      val initJson = json.parseToJsonElement(initResponse).jsonObject
      assertWithMessage("initialize response payload: $initResponse")
        .that(initJson["id"]?.jsonPrimitive?.content)
        .isEqualTo("1")
      assertWithMessage("initialize returned an error: $initResponse")
        .that(initJson["error"])
        .isNull()
      val initResult = initJson["result"]
      assertWithMessage("initialize had no result: $initResponse").that(initResult).isNotNull()
      assertWithMessage("initialize result missing capabilities: $initResponse")
        .that(initResult!!.jsonObject["capabilities"])
        .isNotNull()
      initOk = true

      // 2. initialized notification → PROTOCOL.md § 3 requires this before further requests.
      BundleDaemonStdio.writeFrame(
        stdin,
        BundleDaemonStdio.jsonRpcNotification(method = "initialized"),
      )

      // 3. shutdown request → expect a (typically null) result, then exit.
      BundleDaemonStdio.writeFrame(
        stdin,
        BundleDaemonStdio.jsonRpcRequest(id = 2, method = "shutdown"),
      )
      val shutdownResponse = BundleDaemonStdio.readFrameWithTimeoutMs(stdout, timeoutMs = 10_000)
      val shutdownJson = json.parseToJsonElement(shutdownResponse).jsonObject
      assertWithMessage("shutdown response payload: $shutdownResponse")
        .that(shutdownJson["id"]?.jsonPrimitive?.content)
        .isEqualTo("2")
      assertWithMessage("shutdown returned an error: $shutdownResponse")
        .that(shutdownJson["error"])
        .isNull()
      shutdownOk = true

      BundleDaemonStdio.writeFrame(stdin, BundleDaemonStdio.jsonRpcNotification(method = "exit"))
      stdin.close()

      val exited = proc.waitFor(30, TimeUnit.SECONDS)
      assertWithMessage(
          "daemon did not exit after `exit` notification. stderr:\n${stderrFile.readTextOrEmpty()}"
        )
        .that(exited)
        .isTrue()
      assertWithMessage(
          "daemon exited with non-zero code ${proc.exitValue()}. stderr:\n" +
            stderrFile.readTextOrEmpty()
        )
        .that(proc.exitValue())
        .isEqualTo(0)
    } finally {
      if (proc.isAlive) {
        proc.destroy()
        proc.waitFor(5, TimeUnit.SECONDS)
        if (proc.isAlive) {
          proc.destroyForcibly()
        }
      }
      if (!initOk || !shutdownOk) {
        System.err.println("daemon stderr tail:\n${stderrFile.readTextOrEmpty()}")
      }
    }
  }

  private fun locateCli(): File {
    assertWithMessage("CLI binary path not surfaced via system property")
      .that(cliBinary)
      .isNotEmpty()
    val cli = File(cliBinary)
    assertWithMessage(
        "CLI binary $cliBinary missing — did `:cli:installDist` run? Use " +
          "`./gradlew functionalTestWithBundleRender`"
      )
      .that(cli.isFile)
      .isTrue()
    val installRoot = cli.parentFile.parentFile
    val libDaemonDesktop = installRoot.resolve("lib-daemon-desktop")
    assertWithMessage(
        "lib-daemon-desktop dir missing in CLI distribution at ${libDaemonDesktop.path} — the " +
          "cli build didn't include the `composePreviewDaemonDesktop` configuration outputs."
      )
      .that(libDaemonDesktop.isDirectory)
      .isTrue()
    assertWithMessage("lib-daemon-desktop is empty — :daemon:desktop runtime jars not copied")
      .that(libDaemonDesktop.listFiles { f -> f.name.endsWith(".jar") }.orEmpty().asList())
      .isNotEmpty()
    return cli
  }

  /**
   * Pack a one-preview bundle by driving `compose-preview bundle pack` against a synthetic Compose
   * Desktop project. Identical wire to [BundleRenderEndToEndFunctionalTest]'s pack step — pulled
   * into a helper here so we can hand the resulting `.bundle.png` off to the daemon test without
   * copy-pasting the project-creation block.
   */
  private fun packBundle(cli: File): File {
    val projectDir = BundleE2EFixture.createDesktopProject(tempDir.root)
    val bundle = File(projectDir, "app/build/compose-previews/bundle.png")
    val packBuilder =
      ProcessBuilder(
          cli.absolutePath,
          "bundle",
          "pack",
          "--module",
          ":app",
          "--id",
          BundleE2EFixture.PREVIEW_ID,
          "-o",
          bundle.absolutePath,
          "--verbose",
        )
        .directory(projectDir)
        .redirectErrorStream(true)
    packBuilder.environment()["COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL"] = "1"
    val packProc = packBuilder.start()
    val packOutput = packProc.inputStream.bufferedReader().use { it.readText() }
    val packExit = packProc.waitFor()
    assertWithMessage("compose-preview bundle pack output:\n$packOutput")
      .that(packExit)
      .isEqualTo(0)
    assertWithMessage("bundle missing after pack:\n$packOutput").that(bundle.isFile).isTrue()
    assertThat(bundle.length()).isGreaterThan(0L)
    return bundle
  }

  private fun File.readTextOrEmpty(): String =
    if (this.isFile) this.readText() else "(no stderr captured)"
}
