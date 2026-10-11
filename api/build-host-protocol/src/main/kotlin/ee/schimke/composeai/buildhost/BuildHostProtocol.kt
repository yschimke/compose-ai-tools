package ee.schimke.composeai.buildhost

/**
 * The wire contract between a preview server and a Gradle build host process.
 *
 * The server spawns `compose-preview build-host --stdio` and asks for the seven `ServeBuildHost`
 * operations over newline-delimited JSON, so the Gradle Tooling API stays in compose-ai-tools
 * (`docs/design/REPOSITORY_LAYERS.md`) without the CLI linking a web server. Without a build host
 * the server serves published catalogs and prebuilt bundles. Published from here rather than
 * contracts: see `docs/design/BUILD_HOST_PROTOCOL_PREVIEWMODULE.md`.
 *
 * Framing: one UTF-8 JSON object per line, requests on stdin, responses and events on stdout.
 * Nothing but protocol may be written to the host's stdout: Gradle output is forwarded as
 * [BuildHostEvent.Log], and host diagnostics go to stderr.
 *
 * Correlation: each request's [BuildHostEnvelope.id] is repeated by its response and by events
 * emitted while it runs. Requests are answered in order (they mutate one Gradle build).
 */
public object BuildHostProtocol {

  /**
   * The version this build of the protocol speaks, bumped for any change to messages or field
   * meanings. The handshake requires an exact match (the two sides release independently); a
   * mismatch is a [BuildHostResponse.Failure] and the server falls back to no build host.
   */
  public const val VERSION: Int = 1

  /** The argument that puts the CLI into build-host mode, named once so both sides agree. */
  public const val STDIO_FLAG: String = "--stdio"
}
