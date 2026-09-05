package ee.schimke.composeai.buildhost

/**
 * The wire contract between a preview server and a Gradle **build host** process.
 *
 * The preview server needs Gradle work done — find the project root, list the modules that declare
 * previews, run tasks, build the manifests — and for a long time the only way to get it was for
 * `serve` to *be* a compose-ai-tools CLI command, because the CLI's `ServeCommand` was the sole
 * real implementation of the `ServeBuildHost` interface. That is the forward edge of the dependency
 * cycle in yschimke/compose-preview-server#180: an offline CLI linking a web server so a web server
 * could reach a Gradle driver.
 *
 * This makes the seam a **process boundary** instead of a library one. The server spawns
 * `compose-preview build-host --stdio` and asks for the same seven operations over
 * newline-delimited JSON; the Gradle Tooling API stays in compose-ai-tools, which is where
 * `docs/design/REPOSITORY_LAYERS.md` puts it permanently. Without a build host the server serves
 * published catalogs and prebuilt bundles, which stops being a stubbed-out interface and becomes an
 * honest *no Gradle here*.
 *
 * ### Why this module is here and not in compose-preview-contracts
 *
 * Decided in `docs/design/BUILD_HOST_PROTOCOL_PREVIEWMODULE.md`. The short version: two operations
 * carry `PreviewModule`, which lives in `:preview-data-api` here and not in contracts, and
 * contracts carries no `ee.schimke.composeai` coordinate on any POM. Publishing from here lets a
 * protocol change and its implementation land in one commit compiled against each other while the
 * shape is still being learned; moving it down to contracts later, once it is settled, is
 * mechanical.
 *
 * ### Framing
 *
 * One JSON object per line, UTF-8, on stdin (requests) and stdout (responses and events). Newline
 * framing rather than a length prefix because the payloads are small, the channel is a pipe between
 * two processes on one machine, and a human debugging a stuck build wants to be able to read it.
 *
 * **Nothing but protocol may be written to the host's stdout.** Gradle's own output is captured and
 * forwarded as [BuildHostEvent.Log] rather than inherited, so the server can decide whether to show
 * it (`--progress`) or drop it (`silenceStdout`) — a decision the host cannot make for it. The
 * host's own diagnostics go to stderr, which is never framed and never parsed.
 *
 * ### Correlation
 *
 * Every request carries an [BuildHostEnvelope.id] the response repeats. Requests are answered in
 * order — one build host serves one server, and the operations mutate a Gradle build, so
 * interleaving them would be a bug rather than a feature — but the id is on the wire anyway,
 * because discovering later that you need it is expensive and carrying it is free. Events emitted
 * while an operation runs carry that operation's id, which is what lets the server attribute output
 * to the task that produced it.
 */
public object BuildHostProtocol {

  /**
   * The version this build of the protocol speaks.
   *
   * Bumped for any change to the message set or a field's meaning. The handshake compares it
   * exactly rather than range-checking: the server and the host are released from two repositories
   * on two cadences, so "close enough" is precisely the state that produces a wrong answer instead
   * of an error. A mismatch is reported by [BuildHostResponse.Failure] and the server falls back to
   * serving without a build host.
   */
  public const val VERSION: Int = 1

  /** The argument that puts the CLI into build-host mode, named once so both sides agree. */
  public const val STDIO_FLAG: String = "--stdio"
}
