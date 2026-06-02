package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end coverage for `compose-preview bundle daemon` against **Android** bundles, driven
 * through the actual CLI binary + the Robolectric daemon.
 *
 * This is the test that guards the two halves of Phase 2 that unit/desktop coverage can't reach:
 *
 * 1. **`lib-daemon-android/` is packaged in the CLI dist.** [locateCli] asserts `:cli:installDist`
 *    populated the sidecar (regression: the Android daemon launch dies with an "not packaged yet"
 *    diagnostic, or `ClassNotFoundException` on a half-staged dir).
 * 2. **`BundleDaemonCommand.composeDaemonClasspath` puts the carried IR-replay libs on the parent
 *    `-cp`.** For an IR-backed preview the parent-loaded replay host (`:renderer-android`'s
 *    `TileIrReplayComposable` / the connector's `RemoteComposeIrReplay`) links
 *    `androidx.wear.tiles.renderer.*` / `androidx.compose.remote.player.*`, which live only in the
 *    bundle's carried deps. If those aren't appended to the daemon `-cp`, replay blows up with
 *    `NoClassDefFoundError` at render time — so we render a protolayout (Wear tile) IR preview, a
 *    Remote Compose IR preview, and a classic (non-IR) Compose preview to PNG and assert all three
 *    produce fresh, valid PNGs with no `NoClassDefFoundError` on the daemon's stderr.
 *
 * The bundles are pre-built from the real samples (`:samples:wear`, `:samples:remotecompose`) by
 * the root build's `functionalTestWithAndroidBundleDaemon` task and handed over as paths; the test
 * reads `bundle.json` from each to learn which preview ids are IR-backed (and their format) vs
 * classic. Opt-in via `-Pbundle.daemon.android.e2e=true` (cold-starts a Robolectric daemon JVM and
 * needs a local Android SDK for `android.jar`), keyed to
 * `composeai.functionalTest.androidBundleDaemon`.
 */
class AndroidBundleDaemonRenderFunctionalTest {

  @get:Rule val tempDir: TemporaryFolder = TemporaryFolder()

  private val enabled: Boolean =
    System.getProperty("composeai.functionalTest.androidBundleDaemon", "false") == "true"

  private val cliBinary: String = System.getProperty("composeai.functionalTest.cliBinary", "")
  private val wearBundle: String = System.getProperty("composeai.functionalTest.wearBundle", "")
  private val remoteComposeBundle: String =
    System.getProperty("composeai.functionalTest.remoteComposeBundle", "")

  @Test
  fun `bundle daemon renders protolayout, remotecompose and classic Android previews to PNG`() {
    assumeTrue("Skipping: -Pbundle.daemon.android.e2e=true not set", enabled)

    val cli = locateCli()

    // Tracks which kinds of preview actually rendered across both bundles: protolayout (Wear
    // tile IR), remotecompose (RC doc IR), and classic (reflected app.jar Compose preview).
    val formatsSeen = mutableSetOf<String>()
    renderBundle(cli, File(wearBundle), formatsSeen)
    renderBundle(cli, File(remoteComposeBundle), formatsSeen)

    assertWithMessage("expected a protolayout (Wear tile) IR preview to render. saw: $formatsSeen")
      .that(formatsSeen)
      .contains("protolayout")
    assertWithMessage("expected a remotecompose IR preview to render. saw: $formatsSeen")
      .that(formatsSeen)
      .contains("remotecompose")
    assertWithMessage("expected a classic (non-IR) Compose preview to render. saw: $formatsSeen")
      .that(formatsSeen)
      .contains("classic")
  }

  /**
   * Drive one bundle through the daemon: pick one preview per IR format present plus one classic
   * preview, render them via `renderNow`, and assert each produces a fresh, valid PNG. Records the
   * rendered formats into [formatsSeen].
   */
  private fun renderBundle(cli: File, bundle: File, formatsSeen: MutableSet<String>) {
    assertWithMessage(
        "sample bundle missing: ${bundle.path} — did `:samples:…:composePreviewBundle` run? Use " +
          "`./gradlew functionalTestWithAndroidBundleDaemon`"
      )
      .that(bundle.isFile)
      .isTrue()

    val manifest = readBundleManifest(bundle)
    assertWithMessage("expected an android-backend bundle: ${bundle.path}")
      .that(manifest.backend)
      .isEqualTo("android")
    assertThat(manifest.previewIds).isNotEmpty()

    // One preview per IR format present, plus one classic (non-IR) preview if the bundle has one.
    val selected = LinkedHashSet<String>()
    manifest.formatById.entries.groupBy({ it.value }, { it.key }).forEach { (_, ids) ->
      ids.firstOrNull()?.let { selected.add(it) }
    }
    manifest.previewIds.firstOrNull { it !in manifest.formatById.keys }?.let { selected.add(it) }
    assertWithMessage("no renderable previews discovered in ${bundle.name}")
      .that(selected)
      .isNotEmpty()

    val startMillis = System.currentTimeMillis()
    val stderrFile = tempDir.newFile("daemon-stderr-${System.nanoTime()}.log")
    val proc =
      ProcessBuilder(cli.absolutePath, "bundle", "daemon", bundle.absolutePath, "--verbose")
        .directory(tempDir.root)
        .redirectError(ProcessBuilder.Redirect.to(stderrFile))
        .start()
    val stdin: OutputStream = proc.outputStream
    val stdout: InputStream = BufferedInputStream(proc.inputStream)
    val json = Json { ignoreUnknownKeys = true }

    try {
      // initialize — the Android (Robolectric) daemon cold-starts a sandbox, so be generous.
      val initParams = buildJsonObject {
        put("clientVersion", "android-bundle-daemon-e2e/1")
        put("workspaceRoot", "")
        put("moduleId", "bundle:test")
        put("moduleProjectDir", "")
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
      val initResponse = BundleDaemonStdio.readFrameWithTimeoutMs(stdout, timeoutMs = 180_000)
      val initJson = json.parseToJsonElement(initResponse).jsonObject
      assertWithMessage("initialize returned an error: $initResponse")
        .that(initJson["error"])
        .isNull()
      val capabilities = initJson["result"]?.jsonObject?.get("capabilities")?.jsonObject
      assertWithMessage("initialize result missing capabilities: $initResponse")
        .that(capabilities)
        .isNotNull()
      assertWithMessage("expected android backend daemon: $initResponse")
        .that(capabilities!!["backend"]?.jsonPrimitive?.content)
        .isEqualTo("android")

      BundleDaemonStdio.writeFrame(
        stdin,
        BundleDaemonStdio.jsonRpcNotification(method = "initialized"),
      )

      // renderNow the selected previews; PNGs arrive asynchronously via `renderFinished`.
      val renderParams = buildJsonObject {
        put("previews", JsonArray(selected.map { JsonPrimitive(it) }))
        put("tier", "full")
        put("reason", "android-bundle-daemon-e2e")
      }
      BundleDaemonStdio.writeFrame(
        stdin,
        BundleDaemonStdio.jsonRpcRequest(id = 2, method = "renderNow", params = renderParams),
      )

      var queued: List<String>? = null
      val finished = LinkedHashMap<String, String>() // previewId -> pngPath
      val failed = LinkedHashMap<String, String>() // previewId -> error message
      // Loop until the renderNow result has landed AND every queued preview has a terminal
      // (finished | failed) notification. A wedged render surfaces as a per-frame read timeout.
      while (queued == null || finished.size + failed.size < queued!!.size) {
        val frame = BundleDaemonStdio.readFrameWithTimeoutMs(stdout, timeoutMs = 240_000)
        val obj = json.parseToJsonElement(frame).jsonObject
        val frameId = obj["id"]?.jsonPrimitive?.content
        val method = obj["method"]?.jsonPrimitive?.content
        when {
          frameId == "2" && method == null -> {
            assertWithMessage("renderNow returned an error: $frame").that(obj["error"]).isNull()
            val result = obj["result"]!!.jsonObject
            queued = result["queued"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val rejected = result["rejected"]?.jsonArray?.map { it.toString() } ?: emptyList()
            assertWithMessage("renderNow rejected previews in ${bundle.name}: $rejected")
              .that(rejected)
              .isEmpty()
          }
          method == "renderFinished" -> {
            val params = obj["params"]!!.jsonObject
            finished[params["id"]!!.jsonPrimitive.content] =
              params["pngPath"]!!.jsonPrimitive.content
          }
          method == "renderFailed" -> {
            val params = obj["params"]!!.jsonObject
            failed[params["id"]!!.jsonPrimitive.content] =
              params["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "(no message)"
          }
          else -> {
            // renderStarted / discoveryUpdated / log notifications — not terminal, ignore.
          }
        }
      }

      drainShutdown(stdin, stdout)
      proc.waitFor(30, TimeUnit.SECONDS)

      val stderr = stderrFile.readTextOrEmpty()
      assertWithMessage("renders failed for ${bundle.name}: $failed\nstderr:\n$stderr")
        .that(failed)
        .isEmpty()
      assertWithMessage("not every selected preview rendered for ${bundle.name}\nstderr:\n$stderr")
        .that(finished.keys)
        .containsAtLeastElementsIn(selected)
      for ((id, pngPath) in finished) {
        val png = File(pngPath)
        // Rich diagnostics for the "daemon reported renderFinished but the PNG isn't there" failure
        // (see #1687): the reported path, the preview's IR format, what the daemon actually wrote
        // into the output dir, and the daemon stderr tail (which logs each render's pngPath).
        val parent = png.parentFile
        val dirListing =
          parent
            ?.listFiles()
            ?.sortedBy { it.name }
            ?.joinToString("\n") { "    ${it.name} (${it.length()} bytes)" }
            ?: "    (output dir does not exist: $parent)"
        val missingDiagnostics =
          "render PNG missing for $id at $pngPath (bundle=${bundle.name})\n" +
            "  format=${manifest.formatById[id] ?: "classic"}\n" +
            "  output dir $parent contents:\n$dirListing\n" +
            "  daemon stderr (tail):\n${stderr.takeLast(4000)}"
        assertWithMessage(missingDiagnostics).that(png.isFile).isTrue()
        assertWithMessage("render PNG empty for $id at $pngPath")
          .that(png.length())
          .isGreaterThan(0L)
        assertWithMessage("render output for $id is not a PNG: $pngPath").that(isPng(png)).isTrue()
        assertWithMessage("render PNG for $id is stale (not produced by this run): $pngPath")
          .that(png.lastModified())
          .isAtLeast(startMillis - 5_000)
        formatsSeen.add(manifest.formatById[id] ?: "classic")
      }

      // The exact failure `composeDaemonClasspath` fixes: a parent-loaded IR replay host that can't
      // see the carried player / tiles-renderer libs trips NoClassDefFoundError at replay time.
      assertWithMessage(
          "daemon stderr contained NoClassDefFoundError (bundle=${bundle.name}):\n$stderr"
        )
        .that(stderr)
        .doesNotContain("NoClassDefFoundError")
    } finally {
      if (proc.isAlive) {
        proc.destroy()
        proc.waitFor(5, TimeUnit.SECONDS)
        if (proc.isAlive) proc.destroyForcibly()
      }
    }
  }

  /**
   * Best-effort `shutdown` + `exit` handshake; tolerates trailing notifications still in flight.
   */
  private fun drainShutdown(stdin: OutputStream, stdout: InputStream) {
    BundleDaemonStdio.writeFrame(
      stdin,
      BundleDaemonStdio.jsonRpcRequest(id = 3, method = "shutdown"),
    )
    val json = Json { ignoreUnknownKeys = true }
    // Drain any trailing notifications until the shutdown response (id=3) lands.
    for (i in 0 until 10) {
      val frame = BundleDaemonStdio.readFrameWithTimeoutMs(stdout, timeoutMs = 30_000)
      val id = json.parseToJsonElement(frame).jsonObject["id"]?.jsonPrimitive?.content
      if (id == "3") break
    }
    BundleDaemonStdio.writeFrame(stdin, BundleDaemonStdio.jsonRpcNotification(method = "exit"))
    stdin.close()
  }

  private fun locateCli(): File {
    assertWithMessage("CLI binary path not surfaced via system property")
      .that(cliBinary)
      .isNotEmpty()
    val cli = File(cliBinary)
    assertWithMessage(
        "CLI binary $cliBinary missing — did `:cli:installDist` run? Use " +
          "`./gradlew functionalTestWithAndroidBundleDaemon`"
      )
      .that(cli.isFile)
      .isTrue()
    val installRoot = cli.parentFile.parentFile
    val libDaemonAndroid = installRoot.resolve("lib-daemon-android")
    assertWithMessage(
        "lib-daemon-android dir missing in CLI distribution at ${libDaemonAndroid.path} — the cli " +
          "build didn't stage the `composePreviewDaemonAndroid` configuration (Phase 2 packaging)."
      )
      .that(libDaemonAndroid.isDirectory)
      .isTrue()
    assertWithMessage("lib-daemon-android is empty — :daemon:android runtime jars not staged")
      .that(libDaemonAndroid.listFiles { f -> f.name.endsWith(".jar") }.orEmpty().asList())
      .isNotEmpty()
    return cli
  }

  /**
   * Read the bundle's `bundle.json` — a PNG+ZIP polyglot, so [ZipFile] reads the central directory
   * from the file's tail, ignoring the PNG prefix. Returns the backend, the full preview-id list,
   * and a `previewId → IR format` map (`protolayout` / `remotecompose`) for the IR-backed previews.
   */
  private fun readBundleManifest(bundle: File): BundleManifestInfo {
    val json = Json { ignoreUnknownKeys = true }
    ZipFile(bundle).use { zf ->
      val entry = zf.getEntry("bundle.json") ?: error("bundle.json missing in ${bundle.path}")
      val text = zf.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
      val root = json.parseToJsonElement(text).jsonObject
      val backend = root["backend"]?.jsonPrimitive?.content ?: ""
      val previewIds =
        root["previewIds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
      val formatById =
        root["intermediateRepresentations"]?.jsonArray?.associate {
          val ir = it.jsonObject
          ir["previewId"]!!.jsonPrimitive.content to ir["format"]!!.jsonPrimitive.content
        } ?: emptyMap()
      return BundleManifestInfo(backend, previewIds, formatById)
    }
  }

  private data class BundleManifestInfo(
    val backend: String,
    val previewIds: List<String>,
    val formatById: Map<String, String>,
  )

  private fun isPng(file: File): Boolean {
    if (file.length() < PNG_MAGIC.size) return false
    val head = ByteArray(PNG_MAGIC.size)
    file.inputStream().use { it.read(head) }
    return head.contentEquals(PNG_MAGIC)
  }

  private fun File.readTextOrEmpty(): String =
    if (this.isFile) this.readText() else "(no stderr captured)"

  private companion object {
    private val PNG_MAGIC =
      byteArrayOf(
        0x89.toByte(),
        'P'.code.toByte(),
        'N'.code.toByte(),
        'G'.code.toByte(),
        0x0D,
        0x0A,
        0x1A,
        0x0A,
      )
  }
}
