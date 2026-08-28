package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.TrustStore
import ee.schimke.composeai.bundle.TrustedBranch
import ee.schimke.composeai.bundle.TrustedIdentity
import ee.schimke.composeai.bundle.TrustedKey
import ee.schimke.composeai.io.SystemFileSystem
import kotlinx.serialization.Serializable
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * The `producers.json` file itself — the operator's trust store as an editable document.
 *
 * Mirrors [ServeCatalogsConfigFile] deliberately: same staged-write discipline, same "absent file
 * reads as empty" rule, same Okio [FileSystem] injection so tests drive it with a fake. The trust
 * store used to be baked into the container image, which made adding a producer a code change —
 * open a PR, wait for a release, wait for an image publish, wait for a roll — even though the box
 * could publish a *catalog* at runtime in one HTTP call. That asymmetry made runtime catalog
 * registration close to useless: the new catalog served, but badged `unverified` until an image
 * caught up. Moving this file onto the same config volume as `catalogs.json` closes it.
 */
class ServeTrustStoreFile(
  private val path: Path,
  private val fileSystem: FileSystem = SystemFileSystem,
) {
  val displayPath: String
    get() = path.toString()

  fun exists(): Boolean = fileSystem.exists(path)

  /** Parse the file; an absent file is the empty fail-closed store. Throws on malformed JSON. */
  fun load(): TrustStore {
    if (!fileSystem.exists(path)) return TrustStore.EMPTY
    return TrustStore.parse(fileSystem.read(path) { readUtf8() })
  }

  /**
   * Write [store] back, staged through a sibling temp file + [FileSystem.atomicMove]. A truncated
   * trust store is worse than a truncated catalog list: it fails *closed*, so every catalog on the
   * box would silently drop to `unverified` on the next boot.
   */
  fun save(store: TrustStore) {
    val parent = path.parent
    parent?.let { fileSystem.createDirectories(it) }
    val tmp = if (parent != null) parent / "${path.name}.tmp" else "${path.name}.tmp".toPath()
    fileSystem.write(tmp) { writeUtf8(TrustStore.encode(store)) }
    fileSystem.atomicMove(tmp, path)
  }
}

/**
 * A trust store that can change while the server runs.
 *
 * [TrustStore] is an immutable value read on every verification, and it used to be resolved once
 * into a `by lazy` val at startup — so an edit to producers.json needed a restart to take effect.
 * Consumers ([ServeCatalogStore], [ServeBundleStore]) now take a `() -> TrustStore` and call it per
 * verification, which makes the *next* catalog fetch or bundle upload see an admin change with no
 * restart. Reads are lock-free through the `@Volatile` reference; writers serialise in
 * [ServeTrustAdmin].
 */
class MutableTrustStore(
  initial: TrustStore = TrustStore.EMPTY,
  /**
   * The backing document, when there is one. Given a [source], [get] also picks up an operator's
   * **direct** edit to producers.json — without it, a hand-edit would sit unnoticed until the next
   * admin call or a restart, which is exactly the staleness this class exists to remove.
   */
  private val source: ServeTrustStoreFile? = null,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) {
  @Volatile private var current: TrustStore = initial

  /**
   * The trust store as of now, re-read from [source] on every call.
   *
   * Deliberately not mtime-gated. Filesystem timestamp granularity is coarse enough that two writes
   * inside the same tick are indistinguishable, so a "has it changed?" check can silently miss an
   * edit — the exact staleness this exists to remove. Re-parsing instead is affordable because a
   * verification is *rare and expensive*: it accompanies a catalog branch fetch or the hashing of
   * an uploaded bundle, next to which reading a few KB of JSON does not register.
   */
  fun get(): TrustStore {
    val file = source ?: return current
    // Only a successfully parsed, present file replaces what's in force. A malformed or truncated
    // edit — or a deleted file — keeps the last good store rather than dropping to EMPTY: failing
    // closed here would silently un-trust every catalog on the box mid-flight over a half-saved
    // write. The admin API separately refuses to *write* over an unreadable document, so stale
    // state can't be laundered back onto disk.
    if (file.exists()) {
      runCatching { file.load() }
        .onSuccess { current = it }
        .onFailure { onLog("serve: ignoring unreadable ${file.displayPath}: ${it.message}") }
    }
    return current
  }

  fun set(store: TrustStore) {
    current = store
  }
}

/**
 * One producer entry on the admin API — a flat, discriminated shape covering all three trust bases.
 *
 * Flat rather than a sealed hierarchy because it's also the wire DTO: `kind` picks which fields
 * matter, which keeps the request body obvious to write by hand (`curl -d '{"kind":"branch",…}'`)
 * and keeps one route pair instead of three.
 */
@Serializable
data class AdminTrustEntry(
  /** `branch`, `key`, or `oidc`. */
  val kind: String,
  /** `branch`: the `<owner>/<repo>` pattern. */
  val repo: String? = null,
  /** `branch`: the branch-name pattern; defaults to the [TrustedBranch] default (`*`). */
  val branch: String? = null,
  /** `key`: the pinned key's id. */
  val keyId: String? = null,
  /** `key`: PEM or base64 X.509 SPKI. */
  val publicKey: String? = null,
  /** `key`: an optional human label. */
  val name: String? = null,
  /** `oidc`: the workload-identity pattern. */
  val identity: String? = null,
)

/**
 * Add and remove trusted producers on a **running** server, and persist the result.
 *
 * The runtime half of making the trust store config, exactly as [ServeCatalogAdmin] is for the
 * catalog set. Same contract in both directions: validate first, mutate the live store, then write
 * the file back; a persistence failure downgrades to a warning on an otherwise-successful result
 * rather than rolling back, because the in-memory change is already serving.
 *
 * **This grants more than it looks like.** With `--allow-render-trusted` a trusted branch is
 * eligible for server-side re-render — the box builds and executes that producer's Compose. So the
 * admin token is, on such a box, effectively a code-execution credential. That is why the routes
 * are off unless `--admin-token` is set, why the token is separate from the browse token, and why
 * [TrustStore.validateBranch] refuses a match-everything repo pattern.
 */
class ServeTrustAdmin(
  private val store: MutableTrustStore,
  /** The operator's producers.json; null ⇒ changes are runtime-only and don't survive a restart. */
  private val file: ServeTrustStoreFile?,
  /**
   * Called with the reduced store after trust is **removed**, so the caller can retire anything
   * that was already trusted under the old one. Revocation that only affects future verifications
   * isn't revocation: a loaded catalog keeps its `Trusted` verdict (and any live daemon) in the
   * session registry, and the branch refresher skips a reload while the branch SHA is unchanged —
   * so without this the producer stays executable until its branch moves or the box restarts.
   */
  private val onRevoke: (TrustStore) -> Unit = {},
  private val onLog: (String) -> Unit = { System.err.println(it) },
) {
  /**
   * Serialises the whole load-modify-save, not just the save — same lost-update hazard
   * [ServeCatalogAdmin] guards: two concurrent adds would each load the same document, apply one
   * edit, and atomically move, silently discarding the loser.
   */
  private val lock = Any()

  /** The outcome of an admin mutation, mapped to an HTTP status by the caller. */
  sealed interface Result {
    /** Applied. [warning] flags a non-fatal persistence failure. */
    data class Ok(val summary: String, val warning: String? = null) : Result

    /** Malformed entry — a 400. */
    data class Invalid(val reason: String) : Result

    /** Already trusted (add) or not trusted (remove) — a 409 / 404. */
    data class Conflict(val reason: String) : Result
  }

  /** The producers currently trusted. */
  fun list(): TrustStore = store.get()

  /**
   * Trust [entry]. Idempotency is a conflict, not a silent no-op, so a typo'd repeat is visible.
   */
  fun add(entry: AdminTrustEntry): Result =
    when (entry.kind) {
      "branch" -> addBranch(entry)
      "key" -> addKey(entry)
      "oidc" -> addIdentity(entry)
      else -> Result.Invalid("unknown trust kind '${entry.kind}' (branch, key, or oidc)")
    }

  /** Stop trusting the producer [entry] identifies. */
  fun remove(entry: AdminTrustEntry): Result =
    when (entry.kind) {
      "branch" -> {
        val repo = entry.repo
        if (repo.isNullOrBlank()) Result.Invalid("branch removal needs a repo")
        else
          mutate("branch $repo@${entry.branch ?: "*"}") { current ->
            val match =
              current.branches.firstOrNull {
                it.repo == repo && (entry.branch == null || it.branch == entry.branch)
              } ?: return@mutate null
            current.copy(branches = current.branches - match)
          }
      }
      "key" -> {
        val keyId = entry.keyId
        if (keyId.isNullOrBlank()) Result.Invalid("key removal needs a keyId")
        else
          mutate("key $keyId") { current ->
            val match = current.keys.firstOrNull { it.keyId == keyId } ?: return@mutate null
            current.copy(keys = current.keys - match)
          }
      }
      "oidc" -> {
        val identity = entry.identity
        if (identity.isNullOrBlank()) Result.Invalid("oidc removal needs an identity")
        else
          mutate("oidc $identity") { current ->
            val match = current.oidc.firstOrNull { it.identity == identity } ?: return@mutate null
            current.copy(oidc = current.oidc - match)
          }
      }
      else -> Result.Invalid("unknown trust kind '${entry.kind}' (branch, key, or oidc)")
    }

  private fun addBranch(entry: AdminTrustEntry): Result {
    val repo = entry.repo
    if (repo.isNullOrBlank()) return Result.Invalid("branch entry needs a repo")
    val branch = TrustedBranch(repo = repo, branch = entry.branch ?: "*")
    TrustStore.validateBranch(branch)?.let {
      return Result.Invalid(it)
    }
    return mutate("branch ${branch.repo}@${branch.branch}") { current ->
      if (current.branches.any { it.repo == branch.repo && it.branch == branch.branch }) null
      else current.copy(branches = current.branches + branch)
    }
  }

  private fun addKey(entry: AdminTrustEntry): Result {
    val key =
      TrustedKey(
        keyId = entry.keyId.orEmpty(),
        publicKey = entry.publicKey.orEmpty(),
        name = entry.name,
      )
    TrustStore.validateKey(key)?.let {
      return Result.Invalid(it)
    }
    return mutate("key ${key.keyId}") { current ->
      if (current.keys.any { it.keyId == key.keyId }) null
      else current.copy(keys = current.keys + key)
    }
  }

  private fun addIdentity(entry: AdminTrustEntry): Result {
    val identity = TrustedIdentity(entry.identity.orEmpty())
    TrustStore.validateIdentity(identity)?.let {
      return Result.Invalid(it)
    }
    return mutate("oidc ${identity.identity}") { current ->
      if (current.oidc.any { it.identity == identity.identity }) null
      else current.copy(oidc = current.oidc + identity)
    }
  }

  /**
   * Apply [edit] to the trust store under [lock] and publish the result. [edit] returns null when
   * the change is a no-op (already trusted / not trusted), which becomes a [Result.Conflict].
   *
   * The document is re-read from the file first, not taken from memory, so an operator's hand-edit
   * between admin calls isn't clobbered — the same rule [ServeCatalogAdmin.persist] follows.
   */
  private fun mutate(summary: String, edit: (TrustStore) -> TrustStore?): Result =
    synchronized(lock) {
      val target = file
      // An UNREADABLE existing document aborts the mutation instead of falling back to the cached
      // store. Swallowing the parse error and saving would replace a half-written or malformed
      // producers.json with stale state plus this edit — silently resurrecting entries the operator
      // was in the middle of removing, which is the opposite of the hand-edit rule below. An ABSENT
      // file is different and fine: there's nothing to lose, so it reads as the empty store.
      val current =
        if (target == null) store.get()
        else
          runCatching { target.load() }
            .getOrElse { e ->
              onLog("serve: refusing to rewrite unreadable ${target.displayPath}: ${e.message}")
              return Result.Invalid(
                "trust store ${target.displayPath} is present but unreadable " +
                  "(${e.message ?: "parse failed"}); fix or remove it before editing trust"
              )
            }
      val updated = edit(current) ?: return Result.Conflict("$summary: no change")
      store.set(updated)
      val warning =
        if (target == null) "not persisted: no trust store file is configured"
        else
          runCatching { target.save(updated) }
            .fold({ null }) { e ->
              onLog("serve: could not update ${target.displayPath}: ${e.message}")
              "not persisted: ${e.message ?: "write failed"}"
            }
      onLog("serve: trust $summary updated via admin API")
      // Anything that was trusted only under the old store has to be retired now, not at the next
      // branch move. Runs inside the lock so a concurrent add can't re-trust mid-teardown; failures
      // are logged rather than thrown, since the trust change itself has already taken effect.
      if (isReduction(current, updated)) {
        runCatching { onRevoke(updated) }
          .onFailure { onLog("serve: revocation cleanup after $summary failed: ${it.message}") }
      }
      Result.Ok(summary, warning)
    }

  /** True when [updated] trusts strictly less than [before] — i.e. this was a revocation. */
  private fun isReduction(before: TrustStore, updated: TrustStore): Boolean =
    updated.branches.size < before.branches.size ||
      updated.keys.size < before.keys.size ||
      updated.oidc.size < before.oidc.size
}
