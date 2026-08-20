package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where the CLI keeps the grants a human has approved for it — the client half of
 * [docs/design/AGENT_ACCESS_GRANTS.md](../../../../../../../docs/design/AGENT_ACCESS_GRANTS.md).
 *
 * One JSON file, keyed by **server origin**, at
 * `$XDG_CONFIG_HOME/compose-preview/agent-access.json` (`~/.config/…` when that is unset). Keyed by
 * origin because a token minted by one host means nothing to another, and sending it to the wrong
 * one would be handing a credential to a stranger: [tokenFor] answers only for an exact origin
 * match, so there is no "close enough" path that could cross hosts.
 *
 * The file is created `0600` and re-tightened on every write, because it holds bearer tokens.
 * Best-effort on a filesystem without POSIX permissions (Windows, some mounts) — a failure there
 * must not stop a grant from being saved, but it does earn a warning, since the user is entitled to
 * know their credential is sitting in a world-readable file.
 *
 * Expired entries are dropped on read rather than swept on a schedule: this is a CLI, it runs and
 * exits, and a stale row costs nothing until someone asks about it.
 */
internal class AgentAccessStore(
  private val file: File = defaultFile(),
  private val clock: () -> Long = System::currentTimeMillis,
  private val warn: (String) -> Unit = { System.err.println("compose-preview: $it") },
) {

  @Serializable
  data class Entry(
    /** Normalised origin (`https://preview.coo.ee`) — the key, repeated for readability. */
    val origin: String,
    val token: String,
    val scopes: List<String> = emptyList(),
    val approvedBy: String = "",
    val label: String = "",
    /** Wall-clock epoch millis. Past ⇒ the entry is dropped on the next read. */
    val expiresAtMillis: Long = 0,
  ) {
    fun secondsUntilExpiry(nowMillis: Long): Long =
      ((expiresAtMillis - nowMillis) / 1000).coerceAtLeast(0)
  }

  @Serializable
  private data class Wire(
    val schema: String = SCHEMA_V1,
    val grants: List<Entry> = emptyList(),
  )

  /** Every live grant, expired ones already dropped. */
  fun all(): List<Entry> {
    val now = clock()
    return read().grants.filter { it.expiresAtMillis > now }
  }

  /** The live grant for [origin], or null. Exact origin match — never a prefix or a suffix. */
  fun entryFor(origin: String): Entry? {
    val key = normalizeOrigin(origin) ?: return null
    return all().firstOrNull { it.origin == key }
  }

  fun tokenFor(origin: String): String? = entryFor(origin)?.token

  /**
   * Save (replacing any existing entry for the same origin). Returns false when the write failed.
   */
  fun save(entry: Entry): Boolean {
    val key = normalizeOrigin(entry.origin) ?: return false
    val now = clock()
    val kept = read().grants.filter { it.origin != key && it.expiresAtMillis > now }
    return write(Wire(grants = kept + entry.copy(origin = key)))
  }

  /** Forget the grant for [origin]. Returns true when one was there. */
  fun forget(origin: String): Boolean {
    val key = normalizeOrigin(origin) ?: return false
    val current = read().grants
    val kept = current.filter { it.origin != key }
    if (kept.size == current.size) return false
    write(Wire(grants = kept))
    return true
  }

  /** Forget everything. */
  fun clear(): Boolean = write(Wire())

  private fun read(): Wire {
    if (!file.isFile) return Wire()
    return try {
      JSON.decodeFromString(Wire.serializer(), file.readText())
    } catch (e: Exception) {
      // A corrupt store is not worth failing a command over — it holds only short-lived tokens that
      // can be re-requested — but it must be said out loud, or "auth status" silently reports
      // nothing and the user re-runs a flow that was never going to be read back.
      warn("could not read ${file.path} (${e.message}); treating it as empty")
      Wire()
    }
  }

  private fun write(wire: Wire): Boolean {
    return try {
      file.parentFile?.mkdirs()
      file.writeText(JSON.encodeToString(Wire.serializer(), wire))
      restrictPermissions()
      true
    } catch (e: Exception) {
      warn("could not write ${file.path} (${e.message})")
      false
    }
  }

  /** `0600`, re-applied on every write. Best effort, and loud when it can't be done. */
  private fun restrictPermissions() {
    try {
      val path = file.toPath()
      val view =
        Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView::class.java)
      if (view == null) {
        warn(
          "${file.path} holds access tokens and this filesystem has no POSIX permissions — " +
            "restrict it yourself if others can read it"
        )
        return
      }
      Files.setPosixFilePermissions(
        path,
        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
      )
    } catch (e: Exception) {
      warn("could not restrict permissions on ${file.path} (${e.message}) — it holds access tokens")
    }
  }

  companion object {
    const val SCHEMA_V1 = "compose-preview-agent-access/v1"

    private val JSON = Json {
      ignoreUnknownKeys = true
      prettyPrint = true
      encodeDefaults = true
    }

    /**
     * `$COMPOSE_PREVIEW_AGENT_ACCESS_FILE`, else `$XDG_CONFIG_HOME/compose-preview/…`, else
     * `~/.config/compose-preview/…`. The override exists for CI and for tests; the XDG path is
     * where a user would look for it.
     */
    fun defaultFile(env: (String) -> String? = System::getenv): File {
      env("COMPOSE_PREVIEW_AGENT_ACCESS_FILE")
        ?.takeIf { it.isNotBlank() }
        ?.let {
          return File(it)
        }
      val configHome =
        env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
          ?: (env("HOME")?.takeIf { it.isNotBlank() }?.let { "$it/.config" } ?: ".")
      return File("$configHome/compose-preview/agent-access.json")
    }

    /**
     * `scheme://host[:port]`, lowercased, default ports dropped, path and query discarded.
     *
     * Discarding the path is what makes the key an *origin*: `https://h/a` and `https://h/b` are
     * the same server and must share a grant. Dropping the default port is what stops `https://h`
     * and `https://h:443` from being two entries for one host, which would make "do I have access?"
     * depend on how the user happened to type the URL.
     *
     * Null for anything that isn't an absolute http(s) URL — including a `user:pass@` form, which
     * is refused outright rather than stripped: it means the caller is doing something this store
     * should not quietly normalise away.
     */
    fun normalizeOrigin(raw: String?): String? {
      val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
      val uri = runCatching { java.net.URI(text) }.getOrNull() ?: return null
      val scheme = uri.scheme?.lowercase() ?: return null
      if (scheme != "http" && scheme != "https") return null
      if (uri.userInfo != null) return null
      val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
      val port = uri.port
      val defaultPort = if (scheme == "https") 443 else 80
      return if (port < 0 || port == defaultPort) "$scheme://$host" else "$scheme://$host:$port"
    }
  }
}
