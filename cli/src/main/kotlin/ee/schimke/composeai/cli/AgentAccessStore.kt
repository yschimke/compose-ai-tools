package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.serve.ServeAgentGrantStore
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
/**
 * No safe place to keep credentials could be determined. Carries the remedy, because the only thing
 * the user can do about it is name a location.
 */
internal class NoCredentialHomeException :
  IllegalStateException(
    "no user config directory could be determined (XDG_CONFIG_HOME, HOME and user.home are all " +
      "unset), and credentials will not be written to the working directory. Set " +
      "COMPOSE_PREVIEW_AGENT_ACCESS_FILE to a path you control."
  )

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
    val approvedBy: String = "",
    val label: String = "",
    /** Wall-clock epoch millis. Past ⇒ the entry is dropped on the next read. */
    val expiresAtMillis: Long = 0,
  ) {
    /**
     * The same 12-hex-character SHA-256 prefix the server prints on `/status` and in its log, so a
     * message here names a row a human can actually find. Never the token itself.
     */
    val fingerprint: String
      get() = ServeAgentGrantStore.fingerprintOf(token)

    fun secondsUntilExpiry(nowMillis: Long): Long =
      ((expiresAtMillis - nowMillis) / 1000).coerceAtLeast(0)
  }

  /**
   * A request that has been opened but not yet collected — what `auth request --no-wait` leaves
   * behind so a later invocation can finish the job.
   *
   * Holds the device secret, which is a credential, and is why this file is `0600`: without it a
   * `--no-wait` run would print "re-run auth status when they approve" and then have thrown away
   * the one thing that can redeem the approval.
   */
  @Serializable
  data class Pending(
    val origin: String,
    val requestId: String,
    val deviceSecret: String,
    val userCode: String = "",
    val approveUrl: String = "",
    val label: String = "",
    /**
     * The **approval window** — how long the human has to decide. Drives what `status` displays.
     */
    val expiresAtMillis: Long = 0,
    /**
     * How long this record is worth *polling*, which is deliberately longer than the window above.
     *
     * The server retains an approved-but-uncollected request until its grant expires, precisely so
     * that a decision made in the last seconds still reaches its agent. Dropping the device secret
     * on the window's own deadline put the two halves out of step: the token was still there for
     * the asking and the only thing that could ask had thrown itself away. Defaults to
     * [expiresAtMillis] so a record written by an older CLI behaves exactly as it used to.
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

  /**
   * Every un-collected request still worth polling — bounded by [Pending.retainUntilMillis], not by
   * the human's approval window.
   */
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
   * Remember an opened request so a later invocation can collect its token.
   *
   * Requests **accumulate** rather than replacing each other per origin. Replacing looked tidy and
   * quietly threw away a credential: run `auth request --no-wait` twice against one server and the
   * first link stays approvable, so a human could approve it and mint a live grant whose device
   * secret this store had already discarded. [MAX_PENDING] bounds the pile; the oldest goes first,
   * which is also the one closest to its own deadline.
   */
  fun savePending(pending: Pending): Boolean {
    val key = normalizeOrigin(pending.origin) ?: return false
    return withLock {
      val now = clock()
      val current = read()
      // Only records that are already dead may be swept to make room. `takeLast` dropped the
      // OLDEST live one instead — and its approval link is still valid on the server, so a human
      // could approve a request whose only device secret this had just deleted. When nothing can be
      // freed the save is refused: the caller finds out, rather than a credential going quietly.
      val kept =
        current.pending.filter {
          maxOf(it.retainUntilMillis, it.expiresAtMillis) > now && it.requestId != pending.requestId
        }
      if (kept.size >= MAX_PENDING) return@withLock false
      val retained =
        pending.copy(
          origin = key,
          // Counted from the END of the approval window, not from now. The two sides measure from
          // different instants: this record is created when the request is opened, while the server
          // starts the grant's TTL when it is *approved*. So a request approved in the last seconds
          // of its window with the maximum TTL yields a grant that outlives a creation-anchored
          // deadline by the whole window — and `allPending()` would stop handing over the device
          // secret while the token was still there for the asking. Anchoring here covers every
          // approval the window permits, by construction.
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
   * Save (replacing any existing entry for the same origin). Returns false when the write failed.
   *
   * `open` for one reason: a test needs a *write* to fail while reads keep working, and a wedged
   * filesystem cannot express that — a store that cannot write cannot hold the pending record the
   * test is about either. The failure it stands in for (a full disk, a read-only config dir) is
   * real, and what must happen next — the device secret survives — is worth pinning.
   */
  open fun save(entry: Entry): Boolean {
    val key = normalizeOrigin(entry.origin) ?: return false
    // Read-modify-write under the cross-process lock: two agents finishing `auth request` at the
    // same moment would otherwise both read the old list, and the later writer would silently drop
    // the other's freshly approved grant — a credential lost for no visible reason.
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
      // A failed write is reported as a failure: "forgotten" is a claim about what the *next*
      // process will read, and confirming it while the credential is still on disk is the one
      // answer that must not be given.
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
      // A corrupt store is not worth failing a command over — it holds only short-lived tokens that
      // can be re-requested — but it must be said out loud, or "auth status" silently reports
      // nothing and the user re-runs a flow that was never going to be read back.
      warn("could not read ${file.path} (${e.message}); treating it as empty")
      Wire()
    }
  }

  /**
   * Serialise a read-modify-write against other `compose-preview` processes.
   *
   * An advisory `FileLock` on a sibling `.lock` file — not on the store itself, which is replaced
   * rather than written in place. Best-effort: a filesystem that cannot lock (some network mounts)
   * runs the block anyway, because refusing to save a grant a human just approved is worse than the
   * race it would avoid.
   */
  private fun <T> withLock(block: () -> T): T {
    val lockFile = File(file.parentFile, file.name + ".lock")
    return try {
      file.parentFile?.mkdirs()
      RandomAccessFile(lockFile, "rw").use { raf -> raf.channel.lock().use { block() } }
    } catch (e: Exception) {
      block()
    }
  }

  /**
   * Write via a temp file and an atomic rename, so a concurrent reader sees either the old store or
   * the new one — never a half-written file it would report as empty and then overwrite.
   * Permissions are applied to the temp file *before* the rename, so the credential is never even
   * briefly world-readable.
   */
  private fun write(wire: Wire): Boolean {
    return try {
      file.parentFile?.mkdirs()
      // `createTempFile` demands a prefix of at least three characters, and the store's path is
      // overridable for CI and tests — so a perfectly reasonable
      // `COMPOSE_PREVIEW_AGENT_ACCESS_FILE`
      // of `/tmp/a` made every credential write throw. Padded rather than passed through: the name
      // is a scratch prefix on a file that is renamed away, so it need only be legal.
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

    /**
     * Un-collected requests kept at once. Each is a device secret on disk, so the pile is bounded;
     * high enough that opening a couple of `--no-wait` asks against different servers never loses
     * one, low enough that a runaway script cannot fill the file.
     */
    const val MAX_PENDING = 8

    /**
     * How long a remembered request stays worth polling **past the close of its approval window** —
     * the server's own hard ceiling on a grant's life
     * ([ServeAgentGrantStore.HARD_MAX_GRANT_TTL_SECONDS]). Measured from the window's end rather
     * than from the request's creation, because the server starts a grant's TTL at approval: past
     * window-end plus this, no grant the request could have produced can still be alive, so the
     * record owes nobody.
     */
    const val POLL_RETENTION_SECONDS = 24 * 60 * 60L

    private val JSON = Json {
      ignoreUnknownKeys = true
      prettyPrint = true
      encodeDefaults = true
    }

    /**
     * `$COMPOSE_PREVIEW_AGENT_ACCESS_FILE`, else `$XDG_CONFIG_HOME/compose-preview/…`, else
     * `$HOME/.config/…`, else the JVM's `user.home` **when that is an absolute path**. The override
     * exists for CI and for tests; the XDG path is where a user would look for it.
     *
     * Throws [NoCredentialHomeException] when none of those yields a location, rather than falling
     * back to the working directory — see the body.
     */
    fun defaultFile(
      /**
       * Injected so a test can simulate a JVM with no `user.home`, which it otherwise always has.
       *
       * Deliberately **before** [env] rather than appended: several call sites pass the environment
       * as a trailing lambda, and a new last parameter silently rebinds those to this one instead —
       * they still compile, and they quietly test the wrong thing.
       */
      prop: (String) -> String? = System::getProperty,
      env: (String) -> String? = System::getenv,
    ): File {
      env("COMPOSE_PREVIEW_AGENT_ACCESS_FILE")
        ?.takeIf { it.isNotBlank() }
        ?.let {
          return File(it)
        }
      val configHome =
        env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
          ?: env("HOME")?.takeIf { it.isNotBlank() }?.let { "$it/.config" }
          // The JVM's own view of the user's home, which on Linux comes from the passwd entry
          // rather than the environment — so it survives the minimal `env -i` service context that
          // has neither variable set.
          // Absolute only. `-Duser.home=.` resolves to `./.config/…` — straight back under the
          // working directory, which is the exact outcome this whole path exists to prevent. A
          // relative home is not a home; treat it as absent and let the refusal below fire.
          ?: prop("user.home")
            ?.takeIf { it.isNotBlank() && it != "?" && File(it).isAbsolute }
            ?.let { "$it/.config" }
          // …and if there is genuinely nowhere, REFUSE. The old fallback was `.`, which wrote
          // bearer tokens and device secrets into whatever directory the command happened to run
          // in — for an agent that is a checkout, so the credentials land somewhere that gets
          // archived by CI or committed by the next `git add -A`. Worse, the target directory
          // would then be attacker-influenced by anything that can add a `compose-preview/` path
          // to the tree. Better to store nothing and say so.
          ?: throw NoCredentialHomeException()
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
