package ee.schimke.composeai.mcp

import ee.schimke.composeai.mcp.protocol.ResourceDescriptor

/**
 * Storybook-MCP-compatibility adapter. Storybook shipped an official MCP server (GA in Storybook
 * 10.3) whose tool names an agent's harness learns — `list-all-documentation`, `preview-stories`,
 * `get-documentation-for-story`, `run-story-tests`. This maps that vocabulary onto our existing
 * catalog + render + a11y capabilities so a Storybook-MCP-trained agent drives our server
 * unmodified.
 *
 * Storybook addresses a unit of UI by a **story id** (`title--name`, kebab-cased via CSF's `toId`);
 * we address one by a `compose-preview://<ws>/<module>/<fqn>?config=` URI. This adapter is the
 * bridge: it mints a stable story id per catalogued preview and resolves a story id back to its URI
 * ([resolveUri]) so the alias tools can hand our native handlers a URI.
 *
 * Pure (no daemon/IO) so id minting and resolution unit-test in isolation. It reuses the same CSF
 * `sanitize`/`toId` convention as the serve-side twin `cli.serve.StorybookCompat` (that module
 * isn't on `:mcp`'s classpath, so the tiny primitives are duplicated here — CSF is a fixed
 * standard, so drift risk is low; keep the two in step if the convention ever changes).
 */
object StorybookMcp {

  /** A catalogued preview presented as a Storybook story. */
  data class Story(
    val storyId: String,
    /** The native `compose-preview://…` URI this story resolves to. */
    val uri: String,
    val title: String,
    val name: String,
    /** The preview's fully-qualified function name (for `importPath` / display). */
    val fqn: String,
  )

  /**
   * CSF `sanitize`: lowercase, collapse runs of non-`[a-z0-9]` to a single `-`, trim
   * leading/trailing `-`. Compose FQNs are ASCII, so this reproduces Storybook's id shape.
   */
  fun sanitize(raw: String): String = raw.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

  /** CSF `toId(kind, name)` → `sanitize(kind)--sanitize(name)`, dropping a blank side. */
  fun toId(title: String, name: String): String =
    listOf(sanitize(title), sanitize(name)).filter { it.isNotBlank() }.joinToString("--")

  /**
   * Mint the stories for a catalog ([DaemonMcpServer.catalogResources] output). Resources are
   * already sorted by URI, so iteration — and the deterministic collision suffixes (`-2`, `-3`, …)
   * that keep the story id → URI map 1:1 — are stable across calls as long as the preview set is
   * unchanged. Resources whose URI doesn't parse as a preview URI are skipped.
   */
  fun stories(resources: List<ResourceDescriptor>): List<Story> {
    val used = HashSet<String>()
    val out = ArrayList<Story>(resources.size)
    for (resource in resources) {
      val uri = PreviewUri.parseOrNull(resource.uri) ?: continue
      val title = deriveTitle(uri)
      val name = deriveName(uri, resource.description)
      val base = toId(title, name).ifBlank { sanitize(uri.previewFqn).ifBlank { "preview" } }
      var candidate = base
      var n = 2
      while (!used.add(candidate)) candidate = "$base-${n++}"
      out.add(
        Story(
          storyId = candidate,
          uri = resource.uri,
          title = title,
          name = name,
          fqn = uri.previewFqn,
        )
      )
    }
    return out
  }

  /**
   * Resolve a Storybook story id (or a raw native `compose-preview://…` URI, the deep-link escape
   * hatch) to a native URI. The minted story ids are matched first — they're what
   * `list-all-documentation` advertises — so an advertised id always round-trips; a native URI
   * can't collide with a kebab story id, so the hatch never shadows an advertised story. Null when
   * nothing matches.
   */
  fun resolveUri(storyIdOrUri: String, resources: List<ResourceDescriptor>): String? {
    stories(resources)
      .firstOrNull { it.storyId == storyIdOrUri }
      ?.let {
        return it.uri
      }
    // Native-URI escape hatch: accept it only if it's actually in the catalog.
    if (PreviewUri.parseOrNull(storyIdOrUri) != null) {
      return resources.firstOrNull { it.uri == storyIdOrUri }?.uri
    }
    return null
  }

  /**
   * Sidebar grouping ([Story.title]): `<module>/<Class>` — the Gradle module path
   * (`:samples:android` → `samples/android`) plus the enclosing class/file simple name
   * (`com.example.PreviewsKt.Foo` → `Previews`, trailing `Kt` dropped). The module prefix keeps
   * titles distinct across a multi-module catalog.
   */
  private fun deriveTitle(uri: PreviewUri): String {
    val modulePretty = uri.modulePath.removePrefix(":").replace(':', '/')
    val container = uri.previewFqn.substringBeforeLast('.', missingDelimiterValue = "")
    val classSimple = container.substringAfterLast('.').removeSuffix("Kt")
    val group = classSimple.ifBlank { uri.previewFqn.substringAfterLast('.') }
    return listOf(modulePretty, group).filter { it.isNotBlank() }.joinToString("/")
  }

  /**
   * Story [Story.name]: the preview's display name when the catalog carries one (the resource
   * description, unless it's just the FQN), else the function simple name; a config qualifier is
   * appended so named/parameterised variants stay distinct.
   */
  private fun deriveName(uri: PreviewUri, description: String?): String {
    val fnSimple = uri.previewFqn.substringAfterLast('.')
    val base = description?.trim()?.takeIf { it.isNotBlank() && it != uri.previewFqn } ?: fnSimple
    return if (uri.config != null) "$base (${uri.config})" else base
  }
}
