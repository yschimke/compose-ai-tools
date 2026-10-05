package ee.schimke.composeai.cli

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantProtocol
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** No safe place to keep credentials could be determined; the message carries the remedy. */
internal class NoCredentialHomeException :
  IllegalStateException(
    "no user config directory could be determined (XDG_CONFIG_HOME, HOME and user.home are all " +
      "unset), and credentials will not be written to the working directory. Set " +
      "COMPOSE_PREVIEW_AGENT_ACCESS_FILE to a path you control."
  )

/**
 * Where the CLI keeps the grants a human has approved for it: the client half of
 * [docs/design/AGENT_ACCESS_GRANTS.md](../../../../../../../docs/design/AGENT_ACCESS_GRANTS.md).
 *
 * One JSON file keyed by exact server origin, so a token is never sent to another host. Created
 * `0600` because it holds bearer tokens (warning where POSIX permissions are unavailable). Expired
 * entries are dropped on read.
 */
internal open class AgentAccessStore(
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
    /** Permissions the approver ticked ([AgentGrantCapability]); defaulted for older files. */
    val capabilities: List<String> = emptyList(),
    val approvedBy: String = "",
    val label: String = "",
    /** Wall-clock epoch millis. Past ⇒ the entry is dropped on the next read. */
    val expiresAtMillis: Long = 0,
  ) {
    /** The 12-hex SHA-256 prefix the server shows on `/status`; never the token itself. */
    val fingerprint: String
      get() = AgentGrantProtocol.fingerprintOf(token)

    fun secondsUntilExpiry(nowMillis: Long): Long =
      ((expiresAtMillis - nowMillis) / 1000).coerceAtLeast(0)
  }

  /**
   * A request opened but not yet collected (`auth request --no-wait`). Holds the device secret, the
   * only thing that can redeem the approval.
   */
  @Serializable
  data class Pending(
    val origin: String,
    val requestId: String,
    val deviceSecret: String,
    val userCode: String = "",
    val approveUrl: String = "",
    val label: String = "",
    /** The approval window: how long the human has to decide. Drives what `status` displays. */
    val expiresAtMillis: Long = 0,
    /**
     * How long this record is worth polling: longer than the approval window, because the server
     * keeps an approved-but-uncollected request until its grant expires. Defaults to
     * [expiresAtMillis] for older records.
     */
    val retainUntilMillis: Long = expiresAtMillis,
  ) {
    fun secondsUntilExpiry(nowMillis: Long): Long =
      ((expiresAtMillis - nowMillis) / 1000).coerceAtLeast(0)

    /** True once the human's window has closed — still worth one more poll, but not a wait. */
    fun windowClosed(nowMillis: Long): Boolean = expiresAtMillis <= nowMillis
  }

  @Serializable
  private data class Wire(
    val schema: String = SCHEMA_V1,
    val grants: List<Entry> = emptyList(),
    val pending: List<Pending> = emptyList(),
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

  /** Every un-collected request still worth polling (bounded by [Pending.retainUntilMillis]). */
  fun allPending(): List<Pending> {
    val now = clock()
    return read().pending.filter { maxOf(it.retainUntilMillis, it.expiresAtMillis) > now }
  }

  /** The most recently opened un-collected request for [origin], or null. */
  fun pendingFor(origin: String): Pending? {
    val key = normalizeOrigin(origin) ?: return null
    return allPending().lastOrNull { it.origin == key }
  }

  /**
   * Remember an opened request so a later invocation can collect its token. Requests accumulate per
   * origin, since an earlier link stays approvable on the server. Returns false when [MAX_PENDING]
   * live records already exist: only dead ones are swept, never a secret that can still be
   * redeemed.
   */
  fun savePending(pending: Pending): Boolean {
    val key = normalizeOrigin(pending.origin) ?: return false
    return withLock {
      val now = clock()
      val current = read()
      val kept =
        current.pending.filter {
          maxOf(it.retainUntilMillis, it.expiresAtMillis) > now && it.requestId != pending.requestId
        }
      if (kept.size >= MAX_PENDING) return@withLock false
      val retained =
        pending.copy(
          origin = key,
          // Anchored to the end of the approval window, not now: the server starts the grant's
          // TTL at approval, which may be the window's last second.
          retainUntilMillis =
            maxOf(
              pending.retainUntilMillis,
              maxOf(pending.expiresAtMillis, now) + POLL_RETENTION_SECONDS * 1000,
            ),
        )
      write(current.copy(pending = kept + retained))
    }
  }

  /** Drop one remembered request by id — collected, denied, or expired. */
  fun forgetPendingRequest(requestId: String): Boolean = withLock {
    val current = read()
    val kept = current.pending.filter { it.requestId != requestId }
    if (kept.size == current.pending.size) false else write(current.copy(pending = kept))
  }

  /** Drop every remembered request for [origin] — used when revoking or forgetting a server. */
  fun forgetPending(origin: String): Boolean {
    val key = normalizeOrigin(origin) ?: return false
    return withLock {
      val current = read()
      val kept = current.pending.filter { it.origin != key }
      if (kept.size == current.pending.size) false else write(current.copy(pending = kept))
    }
  }

  /**
   * Save, replacing any entry for the same origin. Returns false when the write failed. `open` so a
   * test can make writes fail while reads keep working.
   */
  open fun save(entry: Entry): Boolean {
    val key = normalizeOrigin(entry.origin) ?: return false
    // Under the cross-process lock so concurrent saves can't drop each other's grants.
    return withLock {
      val now = clock()
      val current = read()
      val kept = current.grants.filter { it.origin != key && it.expiresAtMillis > now }
      write(current.copy(grants = kept + entry.copy(origin = key)))
    }
  }

  /** Forget the grant for [origin]. Returns true when one was there. */
  fun forget(origin: String): Boolean {
    val key = normalizeOrigin(origin) ?: return false
    return withLock {
      val current = read()
      val kept = current.grants.filter { it.origin != key }
      // A failed write must not report the credential as forgotten.
      if (kept.size == current.grants.size) false else write(current.copy(grants = kept))
    }
  }

  /** Forget everything. */
  fun clear(): Boolean = withLock { write(Wire()) }

  private fun read(): Wire {
    if (!file.isFile) return Wire()
    return try {
      JSON.decodeFromString(Wire.serializer(), file.readText())
    } catch (e: Exception) {
      // Only short-lived, re-requestable tokens: warn and carry on rather than fail the command.
      warn("could not read ${file.path} (${e.message}); treating it as empty")
      Wire()
    }
  }

  /**
   * Serialise a read-modify-write against other `compose-preview` processes with an advisory lock
   * on a sibling `.lock` file (the store itself is replaced, not written in place). Where the
   * filesystem cannot lock, the block runs unlocked rather than refusing to save an approved grant.
   */
  private fun <T> withLock(block: () -> T): T {
    val lockFile = File(file.parentFile, file.name + ".lock")
    val raf =
      try {
        file.parentFile?.mkdirs()
        RandomAccessFile(lockFile, "rw")
      } catch (_: Exception) {
        return block()
      }
    return raf.use {
      // Only a failure to *acquire* falls back to running unlocked; an exception from [block] must
      // propagate rather than run the block a second time.
      val lock = runCatching { it.channel.lock() }.getOrNull()
      try {
        block()
      } finally {
        lock?.release()
      }
    }
  }

  /**
   * Write via a temp file and an atomic rename, so a concurrent reader never sees a half-written
   * file. Permissions are applied before the rename, so the credential is never world-readable.
   */
  private fun write(wire: Wire): Boolean {
    return try {
      file.parentFile?.mkdirs()
      // `createTempFile` needs a prefix of at least three characters; the path is overridable.
      val temp = File.createTempFile(file.name.padEnd(3, '-'), ".tmp", file.parentFile)
      try {
        temp.writeText(JSON.encodeToString(Wire.serializer(), wire))
        restrictPermissions(temp)
        Files.move(
          temp.toPath(),
          file.toPath(),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE,
        )
      } catch (e: Exception) {
        temp.delete()
        throw e
      }
      true
    } catch (e: Exception) {
      warn("could not write ${file.path} (${e.message})")
      false
    }
  }

  /** `0600`, applied before the file is moved into place. Best effort, and loud when it can't. */
  private fun restrictPermissions(target: File = file) {
    try {
      val path = target.toPath()
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

    /** Un-collected requests kept at once; each is a device secret on disk. */
    const val MAX_PENDING = 8

    /**
     * How long a request stays worth polling past its approval window: the server's hard ceiling on
     * a grant's life ([AgentGrantProtocol.HARD_MAX_GRANT_TTL_SECONDS]).
     */
    const val POLL_RETENTION_SECONDS = 24 * 60 * 60L

    private val JSON = Json {
      ignoreUnknownKeys = true
      prettyPrint = true
      encodeDefaults = true
    }

    /**
     * `$COMPOSE_PREVIEW_AGENT_ACCESS_FILE`, else `$XDG_CONFIG_HOME/compose-preview/…`, else
     * `$HOME/.config/…`, else the JVM's `user.home`. Throws [NoCredentialHomeException] rather than
     * falling back to the working directory.
     */
    fun defaultFile(
      // Before [env], not appended: callers pass the environment as a trailing lambda.
      prop: (String) -> String? = System::getProperty,
      env: (String) -> String? = System::getenv,
    ): File {
      env("COMPOSE_PREVIEW_AGENT_ACCESS_FILE")
        ?.takeIf { it.isNotBlank() }
        ?.let {
          return File(it)
        }
      // Every candidate must be absolute. A relative one resolves under the working directory,
      // which for an agent is a checkout that CI archives or `git add -A` commits.
      val configHome =
        absoluteHome(env("XDG_CONFIG_HOME"))
          ?: absoluteHome(env("HOME"))?.let { "$it/.config" }
          // From the passwd entry on Linux, so it survives an `env -i` service context.
          ?: absoluteHome(prop("user.home"))?.let { "$it/.config" }
          ?: throw NoCredentialHomeException()
      return File("$configHome/compose-preview/agent-access.json")
    }

    /** [candidate] if it is a usable absolute path (`?` is the JVM's "unknown"), else null. */
    private fun absoluteHome(candidate: String?): String? =
      candidate?.trim()?.takeIf { it.isNotEmpty() && it != "?" && File(it).isAbsolute }

    /**
     * `scheme://host[:port]`, lowercased, default port and path dropped, so equivalent spellings
     * share one grant. Null for anything but an absolute http(s) URL; `user:pass@` is refused
     * rather than stripped.
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
