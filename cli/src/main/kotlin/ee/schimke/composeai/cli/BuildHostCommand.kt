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
import java.util.Optional
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * `compose-preview build-host --stdio`: serves the Gradle operations a preview server needs over a
 * pipe, so the Tooling API stays in this repository (layer 1, `docs/design/REPOSITORY_LAYERS.md`)
 * and the server links only `:build-host-protocol`. A thin adapter over the same [Command] members
 * `ServeCommand` uses.
 */
class BuildHostCommand(args: List<String>) : Command(args) {

  /**
   * Set when this host's project routes to Bazel or Buck2 (the same rule `render` and `list` use):
   * the Gradle-shaped requests are then answered from the Bazel-family driver ([BazelBuildHost]).
   */
  internal var bazel: BazelBuildHost? = runCatching {
    previewBuildRoute(args)
  }
    .getOrNull()
    ?.takeIf { it.kind == ProjectBuildKind.BAZEL || it.kind == ProjectBuildKind.BUCK }
    ?.let { route ->
      BazelBuildHost(
        route.root,
        if (route.kind == ProjectBuildKind.BUCK)
          removeFlag(args, "--build-system") + listOf("--build-system", "buck")
        else args,
      )
    }

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
    // A server that dies outright (SIGKILL, a crash) cannot close our stdin in an orderly way and
    // may leave it open through an intermediate process, so the parent is watched as well
    // (yschimke/compose-ai-tools#6364).
    ParentWatch.start {
      System.err.println("compose-preview build-host: the process that started it exited; exiting")
      exitProcess(0)
    }
    serve(
      System.`in`.bufferedReader(),
      PrintStream(protocol, true, Charsets.UTF_8).writer(),
      onClosedWhileBuilding = {
        System.err.println("compose-preview build-host: stdin closed during a build; exiting")
        exitProcess(0)
      },
    )
    // stdin reached EOF: the server is done with us. Exit even if Tooling API threads linger, so
    // the
    // host never outlives its pipe.
    exitProcess(0)
  }

  /**
   * The request loop, with injected channels for tests. Requests are answered strictly in order:
   * the operations mutate a Gradle build. Cancellation is closing stdin: the loop returns at EOF,
   * and an EOF that arrives while a build is running calls [onClosedWhileBuilding] at once (the
   * host exits) instead of letting the build run on for a reader that has gone. A cancel message
   * that couldn't actually stop the build would be worse than none.
   */
  internal fun serve(
    requests: BufferedReader,
    responses: Writer,
    onClosedWhileBuilding: () -> Unit = {},
  ) {
    val lines = LinkedBlockingQueue<Optional<String>>()
    val building = AtomicBoolean(false)
    val reader = Thread {
      try {
        while (true) {
          val line = requests.readLine() ?: break
          lines.put(Optional.of(line))
        }
      } catch (_: Exception) {
        // A broken pipe is end of input too.
      }
      lines.put(Optional.empty())
      if (building.get()) onClosedWhileBuilding()
    }
    reader.name = "compose-preview-build-host-stdin"
    reader.isDaemon = true
    reader.start()

    var handshaken = false
    while (true) {
      val line = lines.take().orElse(null) ?: return
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
          building.set(request.isBuild())
          handleRequest(request, envelope.id, responses).also {
            if (request is BuildHostRequest.Handshake && it !is BuildHostResponse.Failure) {
              handshaken = true
            }
          }
        } catch (t: Throwable) {
          failure(t)
        } finally {
          building.set(false)
        }
      write(responses, BuildHostEnvelope(envelope.id, response = response))
    }
  }

  /**
   * The requests that run a build and can take minutes. Only these are cut short by EOF; the rest
   * answer at once, so `printf '<handshake>' | compose-preview build-host --stdio` still replies.
   */
  internal fun BuildHostRequest.isBuild(): Boolean =
    this is BuildHostRequest.RunGradleTasks ||
      this is BuildHostRequest.DiscoverAndBuild ||
      this == BuildHostRequest.GradleProjects

  /** Answers one request; a seam so tests can stand in for a long build. */
  internal var handleRequest: (BuildHostRequest, Long, Writer) -> BuildHostResponse = ::handle

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
        if (bazel != null) BuildHostResponse.Strings(emptyList())
        else
          BuildHostResponse.Strings(
            autoInjectInitScriptArgs(args, projectRoot = File(request.projectRoot))
          )

      // No Gradle root in a Bazel workspace: the server then skips its Gradle-only Wasm and git
      // discovery instead of walking `bazel-*` output symlinks.
      BuildHostRequest.GradleProjectRoot ->
        if (bazel != null) BuildHostResponse.Path(null)
        else
        // Normalised via `WireModule.wirePath` like module dirs: the server may not share this
        // working directory, and `findProjectRoot()` can return `<root>/.`.
        BuildHostResponse.Path(findProjectRoot()?.let(WireModule::wirePath))

      BuildHostRequest.GradleVariantArgs ->
        BuildHostResponse.Strings(if (bazel != null) emptyList() else variantGradleArgs())

      is BuildHostRequest.GradleBuildArgs ->
        BuildHostResponse.Strings(
          if (bazel != null) emptyList() else gradleArgsWithForce(request.extra)
        )

      BuildHostRequest.GradleProjects ->
        bazel?.let { BuildHostResponse.Modules(it.modules()) }
          ?: run {
            var found = emptyList<ee.schimke.composeai.previewdata.PreviewModule>()
            withGradle { gradle -> found = gradle.findGradleProjects(timeoutSeconds) }
            BuildHostResponse.Modules(found.map(WireModule::from))
          }

      is BuildHostRequest.RunGradleTasks -> {
        var ok = false
        val bazelHost = bazel
        streamingBuildOutput(id, responses, request.silenceStdout) {
          if (bazelHost != null) {
            ok = bazelHost.runTasks(request.tasks)
            return@streamingBuildOutput
          }
          withGradle(silenceStdout = request.silenceStdout) { gradle ->
            ok = runGradle(gradle, *request.tasks.toTypedArray(), arguments = request.arguments)
          }
        }
        BuildHostResponse.BuildResult(ok)
      }

      is BuildHostRequest.DiscoverAndBuild ->
        bazel?.let { bazelHost ->
          var result: Pair<Boolean, List<WireModuleManifest>> = false to emptyList()
          streamingBuildOutput(id, responses, request.silenceStdout) {
            result = bazelHost.discoverAndBuild()
          }
          BuildHostResponse.Discovery(buildOk = result.first, manifests = result.second)
        }
          ?: run {
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
      Exits when stdin closes (at once if a build is running) or when the process that started
      it exits.

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
