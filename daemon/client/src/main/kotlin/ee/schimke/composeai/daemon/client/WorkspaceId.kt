package ee.schimke.composeai.daemon.client

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
public data class WorkspaceId(val value: String) {
  init {
    require(value.isNotBlank()) { "WorkspaceId.value must not be blank" }
    require(!value.contains('/')) { "WorkspaceId.value must not contain '/' (got '$value')" }
  }

  override fun toString(): String = value

  public companion object {
    /**
     * Builds a deterministic id from [rootProjectName] + the canonical absolute form of [path].
     * Symlink aliases of the same physical path collapse to one id; distinct worktrees stay
     * distinct.
     */
    public fun derive(rootProjectName: String, path: File): WorkspaceId {
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
