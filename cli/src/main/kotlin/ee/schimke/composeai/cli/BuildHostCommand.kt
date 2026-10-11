package ee.schimke.composeai.cli

import ee.schimke.composeai.buildhost.BuildHostCodec
import ee.schimke.composeai.buildhost.BuildHostEnvelope
import ee.schimke.composeai.buildhost.BuildHostEvent
import ee.schimke.composeai.buildhost.BuildHostProtocol
import ee.schimke.composeai.buildhost.BuildHostRequest
import ee.schimke.composeai.buildhost.BuildHostResponse
import ee.schimke.composeai.buildhost.WireModule
import ee.schimke.composeai.buildhost.WireModuleManifest
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.io.Writer

/**
 * `compose-preview build-host --stdio`: serves the Gradle operations a preview server needs over a
 * pipe, so the Tooling API stays in this repository (layer 1, `docs/design/REPOSITORY_LAYERS.md`)
 * and the server links only `:build-host-protocol`. A thin adapter over the same [Command] members
 * `ServeCommand` uses.
 */
class BuildHostCommand(args: List<String>) : Command(args) {

  override fun run() {
    if ("--help" in args || "-h" in args) {
      printUsage()
      return
    }
    require(BuildHostProtocol.STDIO_FLAG in args) {
      "build-host currently speaks only ${BuildHostProtocol.STDIO_FLAG}; pass it explicitly so a " +
        "future transport can be added without changing what this invocation means."
    }
    // Captured before anything can replace it. `serve` writes protocol here and nowhere else.
    val protocol = System.out
    serve(System.`in`.bufferedReader(), PrintStream(protocol, true, Charsets.UTF_8).writer())
  }

  /**
   * The request loop, with injected channels for tests. Requests are answered strictly in order:
   * the operations mutate a Gradle build. Cancellation is closing stdin (the host exits); a cancel
   * message that couldn't actually stop the build would be worse than none.
   */
  internal fun serve(requests: BufferedReader, responses: Writer) {
    var handshaken = false
    while (true) {
      val line = requests.readLine() ?: return
      if (line.isBlank()) continue

      val envelope =
        try {
          BuildHostCodec.decode(line)
        } catch (t: Throwable) {
          // No id to correlate (the envelope itself failed to parse): answer on id 0 and keep
          // serving.
          write(responses, BuildHostEnvelope(id = 0, response = failure(t)))
          continue
        }

      val request = envelope.request
      if (request == null) {
        write(
          responses,
          BuildHostEnvelope(
            envelope.id,
            response =
              BuildHostResponse.Failure(
                "envelope carried no request; the host answers requests and emits events, it does not " +
                  "consume responses"
              ),
          ),
        )
        continue
      }

      // The handshake gates everything, so version skew is reported once, up front.
      if (!handshaken && request !is BuildHostRequest.Handshake) {
        write(
          responses,
          BuildHostEnvelope(
            envelope.id,
            response =
              BuildHostResponse.Failure(
                "handshake first: this host speaks protocol ${BuildHostProtocol.VERSION}"
              ),
          ),
        )
        continue
      }

      val response =
        try {
          handle(request, envelope.id, responses).also {
            if (request is BuildHostRequest.Handshake && it !is BuildHostResponse.Failure) {
              handshaken = true
            }
          }
        } catch (t: Throwable) {
          failure(t)
        }
      write(responses, BuildHostEnvelope(envelope.id, response = response))
    }
  }

  private fun handle(request: BuildHostRequest, id: Long, responses: Writer): BuildHostResponse =
    when (request) {
      is BuildHostRequest.Handshake ->
        if (request.protocolVersion != BuildHostProtocol.VERSION) {
          BuildHostResponse.Failure(
            "protocol mismatch: the server speaks ${request.protocolVersion}, this host speaks " +
              "${BuildHostProtocol.VERSION}. Update whichever is older; serving without a build " +
              "host is the correct fallback until then."
          )
        } else {
          BuildHostResponse.Handshake(BuildHostProtocol.VERSION, BUNDLE_VERSION)
        }

      is BuildHostRequest.AutoInjectInitScriptArgs ->
        BuildHostResponse.Strings(
          autoInjectInitScriptArgs(args, projectRoot = File(request.projectRoot))
        )

      BuildHostRequest.GradleProjectRoot ->
        // Normalised via `WireModule.wirePath` like module dirs: the server may not share this
        // working directory, and `findProjectRoot()` can return `<root>/.`.
        BuildHostResponse.Path(findProjectRoot()?.let(WireModule::wirePath))

      BuildHostRequest.GradleVariantArgs -> BuildHostResponse.Strings(variantGradleArgs())

      is BuildHostRequest.GradleBuildArgs ->
        BuildHostResponse.Strings(gradleArgsWithForce(request.extra))

      BuildHostRequest.GradleProjects -> {
        var found = emptyList<ee.schimke.composeai.previewdata.PreviewModule>()
        withGradle { gradle -> found = gradle.findGradleProjects(timeoutSeconds) }
        BuildHostResponse.Modules(found.map(WireModule::from))
      }

      is BuildHostRequest.RunGradleTasks -> {
        var ok = false
        streamingBuildOutput(id, responses, request.silenceStdout) {
          withGradle(silenceStdout = request.silenceStdout) { gradle ->
            ok = runGradle(gradle, *request.tasks.toTypedArray(), arguments = request.arguments)
          }
        }
        BuildHostResponse.BuildResult(ok)
      }

      is BuildHostRequest.DiscoverAndBuild -> {
        var outcome: RenderModulesOutcome? = null
        streamingBuildOutput(id, responses, request.silenceStdout) {
          outcome = renderAllModules(silenceStdout = request.silenceStdout)
        }
        val settled = outcome
        if (settled == null) {
          BuildHostResponse.Discovery(buildOk = false, manifests = emptyList())
        } else {
          BuildHostResponse.Discovery(
            buildOk = settled.buildOk,
            manifests =
              settled.manifests.map { (module, manifest) ->
                WireModuleManifest(WireModule.from(module), manifest)
              },
          )
        }
      }
    }

  /**
   * Run [block] with `System.out` diverted into [BuildHostEvent.Log] events, since stdout is the
   * protocol channel and raw Gradle output would corrupt it. With [silenceStdout] lines are dropped
   * here (and mostly never produced, since the flag also reaches Gradle).
   */
  private fun streamingBuildOutput(
    id: Long,
    responses: Writer,
    silenceStdout: Boolean,
    block: () -> Unit,
  ) {
    val original = System.out
    val sink = LineSplittingOutputStream { line ->
      if (!silenceStdout) {
        write(responses, BuildHostEnvelope(id, event = BuildHostEvent.Log(line)))
      }
    }
    System.setOut(PrintStream(sink, true, Charsets.UTF_8))
    try {
      block()
    } finally {
      // Flush a trailing partial line before restoring, or it is lost.
      runCatching { sink.flushPartialLine() }
      System.setOut(original)
    }
  }

  private fun write(responses: Writer, envelope: BuildHostEnvelope) {
    responses.write(BuildHostCodec.encode(envelope))
    responses.write("\n")
    responses.flush()
  }

  private fun failure(t: Throwable): BuildHostResponse.Failure =
    BuildHostResponse.Failure(t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.name)

  private fun printUsage() {
    println(
      """
      compose-preview build-host --stdio

      Serve this project's Gradle build to a Compose Preview server over stdin/stdout, so the
      server can discover and build previews without linking the Gradle Tooling API.

      Not run by hand: the server spawns it. Protocol version ${BuildHostProtocol.VERSION},
      newline-delimited JSON. Build output is forwarded as log events, never written to stdout.

      Options:
        --stdio           Speak the protocol on stdin/stdout. Currently required.
        --module <path>   Narrow to one Gradle module, as `serve` does.
        --variant <name>  Select the Android build variant used for previews.
        --help, -h        Show this help.
      """
        .trimIndent()
    )
  }
}

/**
 * Buffers bytes and calls [onLine] per complete line (without terminator; `\r\n` normalised), since
 * a `Log` event carries one line.
 */
internal class LineSplittingOutputStream(private val onLine: (String) -> Unit) : OutputStream() {

  private val buffer = ByteArrayOutputStream()

  override fun write(b: Int) {
    if (b == '\n'.code) {
      emit()
    } else {
      buffer.write(b)
    }
  }

  private fun emit() {
    val line = buffer.toString(Charsets.UTF_8).removeSuffix("\r")
    buffer.reset()
    onLine(line)
  }

  /** Emits whatever is buffered but unterminated. Called when the captured section ends. */
  fun flushPartialLine() {
    if (buffer.size() > 0) emit()
  }
}
