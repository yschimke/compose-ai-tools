package ee.schimke.composeai.cli.serve

/**
 * Resolves the **Figma node a preview is specified by**, when the served catalog declares one, into
 * a deep link the viewer can offer beside its "source" and "report an issue" links.
 *
 * The mapping is already published: a catalog whose producer keeps a `design-map.json` emits its
 * Figma-backed entries into `references/index.json` as `source.provider = "figma"` plus a
 * `figma:<fileKey>/<nodeId>` handle (see `scripts/design-artifacts/design-references.mjs`), and
 * [ServeDesignReferenceStore] keeps those fields. So this is only the last step: turn the handle
 * into a URL, and only when the catalog really names one — a preview whose reference is an HTML
 * export or a plain PNG gets no link rather than a guess.
 *
 * **The server still never talks to Figma.** [DesignReferenceSource.uri] is documented as
 * informational, and that holds here: the browser navigates, nothing is fetched, and no Figma
 * credential exists anywhere in `serve`. Posting a comment through Figma's API would need one — see
 * `docs/public-preview-server.md`.
 *
 * **A catalog is third-party data**, so the handle is parsed strictly rather than trusted: the URL
 * is assembled from a literal origin plus a validated file key and node id, so a catalog that
 * declares `javascript:…` (or anything else shaped wrong) resolves to no link at all instead of an
 * attacker-chosen href on the viewer page.
 */
internal object ServeFigmaSpec {

  /** Figma file keys are URL-safe alphanumerics; anything else is not a key we will link to. */
  private val FILE_KEY = Regex("[A-Za-z0-9_-]{1,64}")

  /** Node ids look like `73:6` (the API/handle form) or `73-6` (the URL form). */
  private val NODE_ID = Regex("[0-9]+[:-][0-9]+")

  /** `figma:<fileKey>/<nodeId>` — the handle `design-map.json` uses and the emitter republishes. */
  private val HANDLE = Regex("^figma:([^/]+)/(.+)$")

  /**
   * A figma.com file/design URL, from which the key is the segment after `/file/` or `/design/`.
   */
  private val FILE_URL = Regex("^https://(?:www\\.)?figma\\.com/(?:file|design)/([^/?#]+)")

  /**
   * The first of [references] that names a Figma node, or null when none does — an all-HTML /
   * all-PNG catalog (and every catalog that publishes no references at all) simply gets no link.
   * First rather than merged: a preview with several references has one canonical spec, and the
   * manifest's order is the producer's own precedence.
   */
  fun of(references: List<DesignReference>): ServeWeb.FigmaSpec? = references.firstNotNullOfOrNull {
    of(it)
  }

  /**
   * [reference] as a deep link, or null when it isn't Figma-backed or its handle doesn't parse.
   * Figma's URL form spells a node id with `-` where the design map and the API use `:`.
   */
  fun of(reference: DesignReference): ServeWeb.FigmaSpec? {
    val source = reference.source
    if (!source.provider.trim().equals("figma", ignoreCase = true)) return null
    val (key, node) = fileKeyAndNode(source) ?: return null
    if (!FILE_KEY.matches(key) || !NODE_ID.matches(node)) return null
    return ServeWeb.FigmaSpec(
      url = "https://www.figma.com/design/$key?node-id=${node.replace(':', '-')}",
      label = reference.label,
    )
  }

  /**
   * A deep link to one node of one file, or null when either part is shaped wrong.
   *
   * The same literal-origin reassembly as [of], exposed for the callers that already hold the pair
   * rather than a [DesignReference] — a page backdrop's placements carry a `figma:<key>/<node>` ref
   * each ([ServeDesignPages]), and every one of them is third-party text.
   */
  fun url(fileKey: String, nodeId: String): String? {
    val key = fileKey.trim()
    val node = nodeId.trim()
    if (!FILE_KEY.matches(key) || !NODE_ID.matches(node)) return null
    return "https://www.figma.com/design/$key?node-id=${node.replace(':', '-')}"
  }

  /**
   * The `(fileKey, nodeId)` pair a Figma-backed reference carries. Two shapes are accepted, both of
   * which real producers emit: the `figma:<key>/<node>` handle, and a figma.com URL paired with a
   * `nodeId` (or `node-id`) attribute — the form the manifest example in the docs uses.
   */
  private fun fileKeyAndNode(source: DesignReferenceSource): Pair<String, String>? {
    val uri = source.uri?.trim().orEmpty()
    val attributeNode =
      (source.attributes["nodeId"] ?: source.attributes["node-id"])?.trim()?.takeIf {
        it.isNotEmpty()
      }
    HANDLE.find(uri)?.let { m ->
      return m.groupValues[1] to (attributeNode ?: m.groupValues[2])
    }
    FILE_URL.find(uri)?.let { m ->
      val node = attributeNode ?: nodeFromQuery(uri) ?: return null
      return m.groupValues[1] to node
    }
    return null
  }

  /** `?node-id=73-6` on an otherwise bare file URL. */
  private fun nodeFromQuery(uri: String): String? =
    Regex("[?&]node-id=([^&#]+)").find(uri)?.groupValues?.get(1)
}
