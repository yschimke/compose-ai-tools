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
 * `compose-preview build-host --stdio` — serves the Gradle operations a preview server needs, over
 * a pipe.
 *
 * The server used to get these by *being* the CLI: `ServeCommand` was the only real implementation
 * of the server's `ServeBuildHost` interface, which is why `serve` could not leave this repository
 * and why the standalone server binary stubbed every method. This command is the same work behind a
 * process boundary instead of a linked interface, so the Gradle Tooling API stays here — layer 1,
 * per `docs/design/REPOSITORY_LAYERS.md` — and the server links only `:build-host-protocol`.
 *
 * It is deliberately a thin adapter. Every operation delegates to the same [Command] members
 * `ServeCommand` delegates to, so there is one implementation of "build the previews" and this
 * translates it rather than reimplementing it.
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
   * The request loop, with its channels injected so a test can drive it without a process.
   *
   * Requests are answered in order and one at a time. That is not a simplification to revisit: the
   * operations mutate a Gradle build, so two in flight at once is a bug rather than throughput.
   *
   * **Cancellation is stdin closing.** There is no cancel message in v1, because the honest
   * implementation of one is killing the Gradle build, and the server already has that lever —
   * close the pipe, and the host exits. A message that only set a flag would be a cancellation that
   * does not cancel, which is worse than not having one.
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
          // No id to correlate against — the envelope is what failed to parse — so answer on id 0
          // and keep serving. Exiting here would turn one bad line into a dead build host.
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

      // The handshake gates everything, so a version skew is reported once, up front, rather than
      // as a puzzling failure several operations into a build.
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
        // Through `WireModule.wirePath` for the same reason module directories are: the server need
        // not share this working directory, and a relative root would resolve against whichever
        // process read it. Normalising is not cosmetic here — `findProjectRoot()` legitimately
        // returns `<root>/.`, so without it the server would see a root that never string-matches
        // the module directories underneath it.
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
   * Runs [block] with `System.out` diverted into [BuildHostEvent.Log] events.
   *
   * This is the load-bearing part of speaking a protocol on stdout. The Gradle plumbing below
   * prints build output to `System.out`, and `System.out` is the protocol channel — inherited, that
   * output would interleave with framed JSON and corrupt the stream on the first task that printed
   * anything.
   *
   * So it is captured and reframed. The server then decides what to do with it, which is the right
   * place for that decision: the host cannot know whether the invocation wants `--progress`.
   *
   * When [silenceStdout] the lines are dropped here rather than forwarded and discarded at the far
   * end — a long build should not push megabytes into a pipe nobody reads. The same flag is passed
   * down to the Gradle call, so most of it is never produced either; this catches what still is.
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
      // Flush a trailing partial line before restoring, or the last line of a build that did not
      // end in a newline is silently lost.
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
 * Buffers bytes and calls [onLine] once per complete line, without the terminator.
 *
 * Line-oriented because the protocol is: a `Log` event carries one line, and the framing supplies
 * the break. `\r\n` is normalised to one line so Gradle output captured on Windows does not arrive
 * with a trailing carriage return baked into every event.
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
