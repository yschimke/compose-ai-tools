package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.serve.RenderOutcome
import ee.schimke.composeai.cli.serve.ServeBundle
import ee.schimke.composeai.cli.serve.ServeHttpServer
import ee.schimke.composeai.cli.serve.ServeMdnsAdvertiser
import ee.schimke.composeai.cli.serve.ServePreview
import ee.schimke.composeai.cli.serve.ServeRenderHost
import ee.schimke.composeai.cli.serve.ServeSessionRegistry
import ee.schimke.composeai.cli.serve.ServeUrls
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.render.session.RenderSessionException
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * `compose-preview serve [--module M] [--id|--filter] [--host H] [--lan] [--port N] [--token T]`
 *
 * Starts a long-lived local HTTP server that renders one module's `@Preview`s on demand and serves
 * them as PNGs with display overrides, so a teammate on the network can open a link and look at a
 * specific preview. Tier 1 (read-only snapshot) of the Remote Session feature — works for both
 * Android/Robolectric and Desktop because it rides the same daemon `RenderSession` path the
 * `render-matrix` / `a11y` commands use.
 *
 * The server is multi-client (stateless HTTP fronting one shared, serialised render session) and
 * serves the module's whole preview set, so switching previews is just navigation.
 */
class ServeCommand(args: List<String>) : Command(args) {

  private val lan: Boolean = "--lan" in args
  private val host: String =
    when {
      lan -> ServeUrls.ALL_INTERFACES
      else -> args.flagValue("--host")?.takeIf { it.isNotBlank() } ?: ServeUrls.LOOPBACK
    }
  private val requestedPort: Int = args.flagValue("--port")?.toIntOrNull() ?: DEFAULT_PORT
  private val tokenOverride: String? = args.flagValue("--token")?.takeIf { it.isNotBlank() }
  private val exportPath: String? = args.flagValue("--export")?.takeIf { it.isNotBlank() }
  private val inlineBundle: Boolean = "--inline" in args

  override fun run() {
    if ("--help" in args || "-h" in args) {
      printUsage()
      return
    }

    // Discover + build the module(s) so manifests exist and previews resolve. `--module` scopes it.
    val outcome = renderAllModules(silenceStdout = false)
    if (!outcome.buildOk) {
      System.err.println("serve: render build failed.")
      exitProcess(2)
    }
    if (outcome.manifests.isEmpty()) {
      System.err.println("serve: no previews discovered.")
      exitProcess(3)
    }
    if (outcome.manifests.size > 1) {
      System.err.println(
        "serve: ${outcome.manifests.size} modules discovered; a server hosts one module. " +
          "Narrow with --module <path>:"
      )
      outcome.manifests.forEach { (m, _) -> System.err.println("  ${m.gradlePath}") }
      exitProcess(1)
    }

    val (module, manifest) = outcome.manifests.single()
    val previews =
      manifest.previews
        .filter { matches(it.id) }
        .map { ServePreview(id = it.id, label = it.functionName.ifBlank { it.id }) }
    if (previews.isEmpty()) {
      System.err.println("serve: no previews matched (--id/--filter excluded them all).")
      exitProcess(3)
    }

    if (!runDaemonStart(module)) {
      System.err.println("serve: composePreviewDaemonStart failed for ${module.gradlePath}.")
      exitProcess(2)
    }

    val descriptor = File(module.projectDir, "build/compose-previews/daemon-launch.json")
    if (!descriptor.isFile) {
      System.err.println("serve: missing daemon-launch.json at ${descriptor.path}")
      exitProcess(2)
    }

    val renderHost =
      try {
        ServeRenderHost.open(
          descriptorPath = descriptor,
          workspaceRoot = module.projectDir,
          workspaceName = module.projectDir.name,
          previews = previews,
          label = module.gradlePath,
          onLog = { System.err.println("[daemon serve] $it") },
        )
      } catch (e: RenderSessionException) {
        System.err.println("serve: failed to open render session (${e.message})")
        exitProcess(2)
      }

    // `--export` reuses the same render session to write a portable bundle (a WebEmbed gallery +
    // the rendered PNGs) and exits — no server. The live link and the offline bundle are then the
    // same render output.
    if (exportPath != null) {
      exportBundle(renderHost, module.gradlePath, exportPath)
      renderHost.close()
      return
    }

    val token = tokenOverride ?: ServeUrls.generateToken()
    // One shared server fronts a session registry rather than a single host: the current module is
    // the pinned default session, and additional tenants (e.g. other modules / PR revisions) can be
    // forked behind the registry's factory in future without spawning another server.
    val registry = ServeSessionRegistry()
    registry.register(module.gradlePath, renderHost)
    val server =
      ServeHttpServer(
        host = host,
        requestedPort = requestedPort,
        token = token,
        sessions = registry,
        defaultSessionId = module.gradlePath,
      )

    // Advertise on the LAN over mDNS when bound to a reachable interface (`--lan`), so the mobile /
    // wear session-viewer clients can discover this server without a typed URL. Best-effort: a null
    // advertiser (no multicast / sandbox) just means discovery stays dark — the server is fine.
    val advertiser =
      if (ServeUrls.isExposed(host)) {
        ServeMdnsAdvertiser.start(
          moduleLabel = module.gradlePath,
          port = server.port,
          previewIds = previews.map { it.id },
          secure = false,
          onLog = { System.err.println("[serve] $it") },
        )
      } else {
        null
      }

    val done = CountDownLatch(1)
    Runtime.getRuntime()
      .addShutdownHook(
        Thread {
          System.err.println("\nserve: shutting down…")
          runCatching { advertiser?.close() }
          runCatching { server.stop() }
          runCatching { registry.close() }
          done.countDown()
        }
      )

    server.start()
    printBanner(module.gradlePath, server.port, token, previews.size)
    done.await()
  }

  /**
   * Match a preview id against `--id` (exact) / `--filter` (substring); all when neither is set.
   */
  private fun matches(id: String): Boolean =
    when {
      exactId != null -> id == exactId
      filter != null -> id.contains(filter, ignoreCase = true)
      else -> true
    }

  private fun runDaemonStart(module: PreviewModule): Boolean {
    var ok = true
    withGradle(silenceStdout = false) { gradle ->
      ok =
        runGradle(
          gradle,
          ":${module.gradlePath}:composePreviewDaemonStart",
          arguments = gradleArgsWithForce(),
        )
    }
    return ok
  }

  private fun printBanner(moduleLabel: String, port: Int, token: String, previewCount: Int) {
    val exposed = ServeUrls.isExposed(host)
    val localHost = if (exposed || host == ServeUrls.LOOPBACK) ServeUrls.LOOPBACK else host
    val localUrl = ServeUrls.landingUrl(ServeUrls.origin(localHost, port), token)

    System.err.println("compose-preview serve — module $moduleLabel")
    System.err.println("  Local:   $localUrl")
    if (exposed) {
      val networks = ServeUrls.siteLocalIpv4Addresses()
      if (networks.isEmpty()) {
        System.err.println("  Network: (no site-local IPv4 address found)")
      } else {
        networks.forEach { ip ->
          System.err.println(
            "  Network: ${ServeUrls.landingUrl(ServeUrls.origin(ip, port), token)}"
          )
        }
      }
      System.err.println(
        "  ⚠ Bound to all interfaces — reachable by anyone on your LAN. The token in the link is " +
          "the only gate; share it only with people you'd let see these previews."
      )
    }
    System.err.println("  Previews: $previewCount")
    System.err.println("  Press Ctrl-C to stop.")
  }

  /**
   * Render every preview once through the held session and write a portable bundle to [path] — a
   * `.zip` when the path ends in `.zip`, otherwise a directory. `--inline` bakes the PNGs into the
   * gallery for a single self-contained `index.html`.
   */
  private fun exportBundle(host: ServeRenderHost, moduleLabel: String, path: String) {
    val built =
      ServeBundle.build(
        previews = host.previews,
        title = moduleLabel,
        modulePath = moduleLabel,
        inline = inlineBundle,
      ) { preview ->
        when (val outcome = host.render(preview.id, PreviewOverrides())) {
          is RenderOutcome.Ok -> outcome.png
          is RenderOutcome.Failed -> {
            System.err.println("serve: ${preview.id} failed to render (${outcome.reason})")
            null
          }
          RenderOutcome.NotFound -> null
        }
      }

    val target = File(path)
    if (path.endsWith(".zip", ignoreCase = true)) {
      target.absoluteFile.parentFile?.mkdirs()
      target.writeBytes(ServeBundle.zip(built.files))
    } else {
      target.mkdirs()
      ServeBundle.writeDir(built.files, target)
    }

    System.err.println(
      "serve: wrote bundle to ${target.path} " +
        "(${built.renderedCount}/${built.previewCount} previews" +
        (if (built.failed.isEmpty()) "" else ", ${built.failed.size} failed") +
        ")"
    )
  }

  private fun printUsage() {
    println(
      """
      compose-preview serve [options]

      Start a local HTTP server that renders one module's @Preview functions on demand and serves
      them as PNGs with display overrides, so you can open (or share) a link to a specific preview.
      Read-only today; bound to loopback unless you opt into LAN exposure.

      Options:
        --module <path>   Module to serve (required when the project has more than one).
        --id <exact>      Only serve this exact preview id.
        --filter <substr> Only serve previews whose id contains this substring.
        --host <addr>     Bind address (default 127.0.0.1 — loopback only).
        --lan             Bind all interfaces (0.0.0.0) so other devices on your network can
                          connect. Prints the token-gated network URL and a security warning.
        --port <n>        Preferred port (default $DEFAULT_PORT; auto-picks the next free one).
        --token <value>   Use a fixed token instead of a freshly generated one (stable links).
        --export <path>   Don't serve: render every preview once and write a portable bundle (a
                          self-contained web gallery + PNGs) to <path>. A '.zip' path writes a zip;
                          any other path writes a directory. The live server also offers this at
                          GET /bundle.zip.
        --inline          With --export, bake the PNGs into the gallery for a single self-contained
                          index.html (vs. separate previews/<id>.png files).

      The shareable link carries an unguessable token; requests without it get 404.
      """
        .trimIndent()
    )
  }

  private companion object {
    const val DEFAULT_PORT = 8723
  }
}
