package ee.schimke.composeai.cli.serve

import java.util.Properties

/**
 * Version the preview server reports to its clients — in `/version`, in the session-viewer
 * handshake, and in the bug-report bundle.
 *
 * Deliberately the server's own constant rather than `:cli`'s `SERVE_VERSION`, even though the two
 * resolve to the same string today because `serve` still ships inside the CLI jar. #3824 splits the
 * server into its own build, and on that day "the version the server reports" and "the version of
 * the CLI that happens to launch it" stop being the same fact: a client talking to a deployed
 * server is asking what the *server* is, and the seam register counted every one of these reads as
 * the server reaching into the CLI for an answer only the server has.
 *
 * Resolved from the same `cli-version.properties` resource for now, which keeps the reported value
 * byte-identical to what it was. When `serve` becomes its own module it generates its own resource
 * and this constant stops pointing at the CLI's — a one-line change here rather than at the ~30
 * call sites that read it.
 */
internal val SERVE_VERSION: String by lazy {
  val props = Properties()
  val stream =
    object {}
      .javaClass
      .classLoader
      .getResourceAsStream("ee/schimke/composeai/cli/cli-version.properties")
      ?: error("cli-version.properties missing from compose-preview jar")
  stream.use { props.load(it) }
  props.getProperty("version") ?: error("version property missing from cli-version.properties")
}
