package ee.schimke.composeai.render.cli

import ee.schimke.composeai.daemon.protocol.RenderTier
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * CLI entry point over [SubprocessRenderSessions.open] for non-Gradle build systems. A Bazel
 * `genrule` or an Amper task can shell out here to drive a render against an existing
 * `daemon-launch.json` without buying into a Kotlin/JVM client.
 *
 * The published `ee.schimke.composeai:render-cli` JAR is a **slim library JAR** (no shaded
 * uber-JAR, no `Class-Path:` manifest entry). The intended invocation is therefore:
 * ```
 * java -cp <resolved-classpath> ee.schimke.composeai.render.cli.RenderCli \
 *   --descriptor <daemon-launch.json> \
 *   --workspace-root <dir> \
 *   --previews <id>[,<id>...] \
 *   [--tier FULL|FAST] \
 *   [--reason <text>] \
 *   [--timeout-seconds 60] \
 *   [--workspace-name <name>]
 * ```
 *
 * where `<resolved-classpath>` is the runtime closure of `ee.schimke.composeai:render-cli` as
 * resolved by the caller's dep system (Bazel `rules_jvm_external`, Amper m2 cache, etc.) and joined
 * with the platform-appropriate `File.pathSeparator`. `java -jar <artifact>.jar` against the bare
 * published JAR will fail with `NoClassDefFoundError` — see the "CLI invocation" section in
 * `docs/NON_GRADLE_INTEGRATION.md`.
 *
 * `--previews` accepts a comma-separated list and can be repeated. The CLI waits for one terminal
 * notification per requested preview id — `renderFinished` (success) or `renderFailed` (the
 * composition threw) — prints the resulting PNG path to stdout (`<id>\t<pngPath>`), and exits 0
 * when every render succeeded. A `renderFailed` ends the wait immediately and reports the daemon's
 * error message on stderr rather than sitting out `--timeout-seconds`.
 *
 * Exit codes: `0` = all renders succeeded, `1` = at least one render rejected, failed, or timed
 * out, `2` = argument parsing failure.
 */
public object RenderCli {

  @JvmStatic
  public fun main(args: Array<String>) {
    val parsed =
      try {
        parse(args)
      } catch (e: ArgError) {
        System.err.println("render-cli: ${e.message}")
        printUsage(System.err)
        exitProcess(2)
      }

    exitProcess(run(parsed))
  }

  /** Returns the process exit code. Extracted for testability. */
  internal fun run(parsed: ParsedArgs): Int {
    val pending = ConcurrentHashMap.newKeySet<String>().apply { addAll(parsed.previewIds) }
    val results = ConcurrentHashMap<String, String>()
    val failures = ConcurrentHashMap<String, String>()
    val latch = CountDownLatch(parsed.previewIds.size)

    SubprocessRenderSessions.open(
        RenderSessionConfig(
          descriptorPath = parsed.descriptor,
          workspaceRoot = parsed.workspaceRoot,
          workspaceName = parsed.workspaceName,
          logSink = { line -> System.err.println("[daemon] $line") },
        )
      )
      .use { session ->
        session
          .onNotification { method, params ->
            if (params == null) return@onNotification
            val id = params["id"]?.jsonPrimitive?.contentOrNull ?: return@onNotification
            if (id !in pending) return@onNotification
            // The daemon owes exactly one terminal event per queued render: `renderFinished` with a
            // pngPath, or `renderFailed` when the composition throws. Releasing the wait on the
            // failure too turns a broken preview into an immediate, explanatory exit 1 instead of
            // sitting out `--timeout-seconds` for a render the daemon already reported dead.
            when (method) {
              "renderFinished" -> {
                val pngPath =
                  params["pngPath"]?.jsonPrimitive?.contentOrNull ?: return@onNotification
                results[id] = pngPath
              }
              "renderFailed" ->
                failures[id] =
                  params["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: "daemon reported renderFailed"
              else -> return@onNotification
            }
            pending.remove(id)
            latch.countDown()
          }
          .use {
            val ack =
              session.renderNow(
                previewIds = parsed.previewIds,
                tier = parsed.tier,
                reason = parsed.reason,
              )
            if (ack.rejected.isNotEmpty()) {
              for (rejected in ack.rejected) {
                System.err.println("render-cli: rejected ${rejected.id}: ${rejected.reason}")
              }
              return 1
            }

            val finished = latch.await(parsed.timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
              System.err.println(
                "render-cli: timed out after ${parsed.timeoutSeconds}s waiting for: " +
                  pending.joinToString(",")
              )
              return 1
            }

            if (failures.isNotEmpty()) {
              for ((id, message) in failures) {
                System.err.println("render-cli: failed $id: $message")
              }
              return 1
            }

            for (id in parsed.previewIds) {
              println("$id\t${results.getValue(id)}")
            }
            return 0
          }
      }
  }

  internal data class ParsedArgs(
    val descriptor: File,
    val workspaceRoot: File,
    val workspaceName: String,
    val previewIds: List<String>,
    val tier: RenderTier,
    val reason: String?,
    val timeoutSeconds: Long,
  )

  internal class ArgError(message: String) : RuntimeException(message)

  internal fun parse(args: Array<String>): ParsedArgs {
    var descriptor: File? = null
    var workspaceRoot: File? = null
    var workspaceName: String? = null
    val previewIds = mutableListOf<String>()
    var tier = RenderTier.FULL
    var reason: String? = null
    var timeoutSeconds = 60L

    var i = 0
    while (i < args.size) {
      val arg = args[i]
      when (arg) {
        "--descriptor" -> descriptor = File(requireValue(args, i))
        "--workspace-root" -> workspaceRoot = File(requireValue(args, i))
        "--workspace-name" -> workspaceName = requireValue(args, i)
        "--previews" -> previewIds += requireValue(args, i).split(',').filter { it.isNotEmpty() }
        "--tier" ->
          tier =
            try {
              RenderTier.valueOf(requireValue(args, i))
            } catch (_: IllegalArgumentException) {
              throw ArgError("--tier must be one of ${RenderTier.values().joinToString(",")}")
            }
        "--reason" -> reason = requireValue(args, i)
        "--timeout-seconds" ->
          timeoutSeconds =
            requireValue(args, i).toLongOrNull()?.takeIf { it > 0 }
              ?: throw ArgError("--timeout-seconds must be a positive integer")
        "-h",
        "--help" -> {
          printUsage(System.out)
          exitProcess(0)
        }
        else -> throw ArgError("unknown argument: $arg")
      }
      i += 2
    }

    val descriptorFile = descriptor ?: throw ArgError("--descriptor is required")
    val root = workspaceRoot ?: throw ArgError("--workspace-root is required")
    // Default workspace name to the root dir's basename — matches the convention
    // `SubprocessRenderSessions`
    // uses when callers leave the field blank. Override with `--workspace-name` only when the
    // build system has a stable identifier worth surfacing in daemon logs.
    val name = workspaceName ?: root.name
    if (previewIds.isEmpty()) throw ArgError("--previews requires at least one id")

    return ParsedArgs(
      descriptor = descriptorFile,
      workspaceRoot = root,
      workspaceName = name,
      previewIds = previewIds,
      tier = tier,
      reason = reason,
      timeoutSeconds = timeoutSeconds,
    )
  }

  private fun requireValue(args: Array<String>, i: Int): String {
    if (i + 1 >= args.size) throw ArgError("${args[i]} requires a value")
    return args[i + 1]
  }

  private fun printUsage(out: java.io.PrintStream) {
    out.println(
      """
      Usage: java -cp <resolved-classpath> ee.schimke.composeai.render.cli.RenderCli [options]

      Required:
        --descriptor <path>         Absolute path to daemon-launch.json.
        --workspace-root <dir>      Workspace root (the repo root containing the module).
        --previews <id>[,<id>...]   Comma-separated preview ids to render. Repeatable.

      Optional:
        --workspace-name <name>     Stable workspace identifier; defaults to the basename of
                                    --workspace-root.
        --tier FULL|FAST            Render tier; defaults to FULL.
        --reason <text>             Free-form reason surfaced in daemon logs.
        --timeout-seconds <n>       Wait at most this long for every requested render to
                                    emit `renderFinished`; defaults to 60.
        --help, -h                  Print this message.

      Output:
        One `<id>\t<pngPath>` line per successful render on stdout.
        Daemon stderr is mirrored to render-cli's stderr.

      Exit codes: 0 = success, 1 = any render rejected/failed/timed out, 2 = arg error.
      """
        .trimIndent()
    )
  }
}
