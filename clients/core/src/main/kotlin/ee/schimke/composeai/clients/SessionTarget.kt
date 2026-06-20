package ee.schimke.composeai.clients

/**
 * What a session link asks the server to stream. A `compose-preview serve` instance can be driven
 * two ways, so a tapped link resolves to one of:
 *
 * - [Preview] — a preview id on a module the server is **already** running. This is the serve
 *   stream spike's `WS /ws/{previewId}` lane (PR #1989).
 * - [Bundle] — a **portable preview bundle** (`docs/portable-bundles.md`) the server should fetch
 *   and **start a session over** before streaming. The bundle is self-contained (manifest +
 *   classpath + baked frames), so a link can point at one sitting on any reachable host and a
 *   server "somewhere" spins it up on demand — the client never needs the project checked out.
 *   Resolves to the (forward-looking) `WS /ws/bundle?src=…` entrypoint.
 */
sealed interface SessionTarget {
  data class Preview(val previewId: String) : SessionTarget {
    init {
      require(previewId.isNotBlank()) { "previewId is blank" }
    }
  }

  data class Bundle(
    /** Where the server fetches the bundle from — an `http(s)`/`file` URL or a resolvable id. */
    val src: String,
    /** Which preview inside the bundle to open; null ⇒ the bundle's cover preview. */
    val previewId: String? = null,
  ) : SessionTarget {
    init {
      require(src.isNotBlank()) { "bundle src is blank" }
    }
  }

  /** A short, human-facing label for the connect UI / status chrome. */
  val label: String
    get() =
      when (this) {
        is Preview -> previewId
        is Bundle -> previewId ?: src.substringAfterLast('/').ifBlank { src }
      }
}
