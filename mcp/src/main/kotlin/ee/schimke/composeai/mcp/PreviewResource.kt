package ee.schimke.composeai.mcp

import java.io.File
import java.security.MessageDigest

/**
 * Stable id derived from a workspace's canonical absolute path. Two worktrees of the same repo
 * (different paths, same `rootProject.name`) get distinct ids by construction — see
 * docs/daemon/MCP.md and the chat thread leading up to this PR.
 *
 * Format: `<rootProjectName>-<8-char-hash>`. The hash is **always** present (not just on
 * collisions) so the id encodes path identity unconditionally.
 */
data class WorkspaceId(val value: String) {
  init {
    require(value.isNotBlank()) { "WorkspaceId.value must not be blank" }
    require(!value.contains('/')) { "WorkspaceId.value must not contain '/' (got '$value')" }
  }

  override fun toString(): String = value

  companion object {
    /**
     * Builds a deterministic id from [rootProjectName] + the canonical absolute form of [path].
     * Symlink aliases of the same physical path collapse to one id; distinct worktrees stay
     * distinct.
     */
    fun derive(rootProjectName: String, path: File): WorkspaceId {
      require(rootProjectName.isNotBlank()) { "rootProjectName must not be blank" }
      val canonical = runCatching { path.canonicalFile }.getOrDefault(path.absoluteFile)
      val hash = sha256Hex(canonical.absolutePath).take(SHORT_HASH_CHARS)
      val sanitisedName = rootProjectName.replace(Regex("[^A-Za-z0-9_.-]"), "-")
      return WorkspaceId("$sanitisedName-$hash")
    }

    private const val SHORT_HASH_CHARS = 8

    private fun sha256Hex(s: String): String {
      val bytes = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
      return bytes.joinToString("") { "%02x".format(it) }
    }
  }
}

/**
 * Parsed `compose-preview://<workspace>/<module>/<fqn>?config=<qualifier>` URI. The three-segment
 * form is load-bearing for multi-workspace operation: a single MCP server can host previews from
 * many workspaces simultaneously, and the workspace segment is what disambiguates them.
 *
 * `module` keeps the leading `:` from a Gradle path encoded as `_` to play well with URL parsers
 * (`:samples:android` becomes `_samples_android` on the wire). The reverse maps back when the
 * supervisor needs to invoke a Gradle path.
 */
data class PreviewUri(
  val workspaceId: WorkspaceId,
  val modulePath: String,
  val previewFqn: String,
  val config: String? = null,
) {
  init {
    require(modulePath.startsWith(":")) {
      "PreviewUri.modulePath must start with ':' (got '$modulePath')"
    }
    require(!previewFqn.contains('/') && !previewFqn.contains('?')) {
      "PreviewUri.previewFqn must not contain '/' or '?' (got '$previewFqn')"
    }
  }

  fun toUri(): String {
    val moduleSegment = encodeModule(modulePath)
    val base = "$SCHEME://${workspaceId.value}/$moduleSegment/$previewFqn"
    return if (config == null) base else "$base?config=$config"
  }

  override fun toString(): String = toUri()

  companion object {
    const val SCHEME: String = "compose-preview"

    /**
     * Strict parser. Returns null on malformed input rather than throwing — `resources/read` and
     * the watch tools should surface "invalid URI" as a typed protocol error, not a stack trace.
     */
    fun parseOrNull(s: String): PreviewUri? {
      val prefix = "$SCHEME://"
      if (!s.startsWith(prefix)) return null
      val rest = s.removePrefix(prefix)
      val (path, query) = rest.split('?', limit = 2).let { it[0] to it.getOrNull(1) }
      val parts = path.split('/')
      if (parts.size < 3) return null
      val workspace = parts[0]
      val moduleEncoded = parts[1]
      val fqn = parts.subList(2, parts.size).joinToString("/")
      if (workspace.isBlank() || moduleEncoded.isBlank() || fqn.isBlank()) return null
      val configValue =
        query
          ?.split('&')
          ?.firstOrNull { it.startsWith("config=") }
          ?.removePrefix("config=")
          ?.takeIf { it.isNotEmpty() }
      return PreviewUri(
        workspaceId = WorkspaceId(workspace),
        modulePath = decodeModule(moduleEncoded),
        previewFqn = fqn,
        config = configValue,
      )
    }

    fun parse(s: String): PreviewUri =
      requireNotNull(parseOrNull(s)) { "Malformed compose-preview URI: '$s'" }

    /**
     * `:samples:android` → `_samples_android`. Lossy iff a Gradle path contains `_`, which it
     * can't.
     */
    fun encodeModule(modulePath: String): String = modulePath.replace(':', '_')

    /** Reverse of [encodeModule]. */
    fun decodeModule(encoded: String): String =
      encoded.replace('_', ':').let { if (it.startsWith(":")) it else ":$it" }
  }
}

/**
 * History resource URI — `compose-preview-history://<workspace>/<module>/<previewFqn>/<entryId>`.
 * Distinct from [PreviewUri] so clients can disambiguate "current state" (live preview) from
 * "snapshot" (historical entry). Per [HISTORY.md § Layer 3 — MCP mapping](
 * ../../../../../../docs/daemon/HISTORY.md#layer-3--mcp-mapping).
 *
 * The `entryId` is the stable hash-based id the daemon attaches to each `HistoryEntry`; it's opaque
 * to MCP — the supervisor passes it back to the daemon's `history/read` / `history/diff` verbatim.
 * We round-trip the value through the URI without any encoding because the daemon's id format
 * already excludes `/` and `?`.
 */
class HistoryUri(
  val workspaceId: WorkspaceId,
  modulePath: String,
  val previewFqn: String,
  val entryId: String,
) {
  val modulePath: String = normalizeModulePath(modulePath)

  init {
    require(!previewFqn.contains('/') && !previewFqn.contains('?')) {
      "HistoryUri.previewFqn must not contain '/' or '?' (got '$previewFqn')"
    }
    require(!entryId.contains('/') && !entryId.contains('?')) {
      "HistoryUri.entryId must not contain '/' or '?' (got '$entryId')"
    }
  }

  fun toUri(): String =
    "$SCHEME://${workspaceId.value}/${PreviewUri.encodeModule(modulePath)}/$previewFqn/$entryId"

  override fun toString(): String = toUri()

  companion object {
    const val SCHEME: String = "compose-preview-history"

    private fun normalizeModulePath(modulePath: String): String {
      val trimmed = modulePath.trim()
      require(trimmed.isNotEmpty()) { "HistoryUri.modulePath must not be blank" }
      return if (trimmed.startsWith(":")) trimmed else ":$trimmed"
    }

    fun parseOrNull(s: String): HistoryUri? {
      val prefix = "$SCHEME://"
      if (!s.startsWith(prefix)) return null
      val parts = s.removePrefix(prefix).split('/')
      if (parts.size < 4) return null
      val workspace = parts[0]
      val moduleEncoded = parts[1]
      val fqn = parts[2]
      val entry = parts.subList(3, parts.size).joinToString("/")
      if (workspace.isBlank() || moduleEncoded.isBlank() || fqn.isBlank() || entry.isBlank())
        return null
      return HistoryUri(
        workspaceId = WorkspaceId(workspace),
        modulePath = PreviewUri.decodeModule(moduleEncoded),
        previewFqn = fqn,
        entryId = entry,
      )
    }

    fun parse(s: String): HistoryUri =
      requireNotNull(parseOrNull(s)) { "Malformed compose-preview-history URI: '$s'" }
  }
}

/**
 * Glob over preview FQN — used by the `watch` tool to register an "area of interest". A single `*`
 * matches any sequence of non-`.` characters; `**` matches anything including dots; `?` matches one
 * non-`.` character. Matching is case-sensitive — Kotlin FQNs are.
 *
 * The glob is always evaluated against `previewFqn`; the workspace + module dimensions are filtered
 * separately by the watch tool itself.
 */
class FqnGlob(val pattern: String) {
  private val regex: Regex = compile(pattern)

  fun matches(fqn: String): Boolean = regex.matches(fqn)

  override fun toString(): String = pattern

  companion object {
    private fun compile(pattern: String): Regex {
      val sb = StringBuilder("^")
      var i = 0
      while (i < pattern.length) {
        when (val c = pattern[i]) {
          '*' ->
            if (i + 1 < pattern.length && pattern[i + 1] == '*') {
              sb.append(".*")
              i++
            } else {
              sb.append("[^.]*")
            }
          '?' -> sb.append("[^.]")
          '.',
          '(',
          ')',
          '[',
          ']',
          '{',
          '}',
          '+',
          '|',
          '^',
          '$',
          '\\' -> {
            sb.append('\\')
            sb.append(c)
          }
          else -> sb.append(c)
        }
        i++
      }
      sb.append('$')
      return Regex(sb.toString())
    }
  }
}
