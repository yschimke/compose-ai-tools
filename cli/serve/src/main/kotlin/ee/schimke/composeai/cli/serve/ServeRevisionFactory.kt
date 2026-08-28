package ee.schimke.composeai.cli.serve

import java.io.File

/** A module to serve, identified by its Gradle path and its directory relative to the repo root. */
data class ServeModuleRef(val gradlePath: String, val relativePath: String)

/** A revision built into a daemon-ready module: where its descriptor + previews live. */
data class BuiltRevision(
  /** The module's project directory inside the checkout (holds `build/compose-previews/`). */
  val moduleDir: File,
  /** `build/compose-previews/daemon-launch.json` for the built module. */
  val descriptor: File,
  val previews: List<ServePreview>,
  /** App-declared `@ThemeCatalog` themes discovered for the module (module-global). */
  val declaredThemes: List<ServeTheme> = emptyList(),
)

/**
 * Builds [module] inside an already-checked-out [worktreeDir]; null when the build/discovery fails.
 *
 * [isSecurityChecked] is a required, greppable audit marker (no runtime enforcement): the caller
 * passes `true` only once the revision has cleared policy (here: [GitWorktrees]' ref allowlist),
 * because building runs the checked-out revision's own Gradle = code execution.
 */
fun interface RevisionBuilder {
  fun build(worktreeDir: File, module: ServeModuleRef, isSecurityChecked: Boolean): BuiltRevision?
}

/**
 * "Project mode" [ServeSessionFactory]: resolves a session id — a git revision (sha/ref/tag) of the
 * **same project** — to a [ServeSessionState], so one shared server can serve any revision of a
 * repo on demand (the building block for live PR-preview links). The state is what the registry
 * caches, suspends, and resumes, so each revision is *built* at most once (this factory runs once
 * per id) even as its daemon is suspended and resumed.
 *
 * The pieces are injected so the orchestration is unit-testable without git or Gradle: [worktrees]
 * checks the revision out into a cache directory (one worktree per resolved commit) and [builder]
 * builds + discovers the module in that checkout.
 */
class ServeRevisionFactory(
  private val worktrees: GitWorktrees,
  private val builder: RevisionBuilder,
  private val module: ServeModuleRef,
  private val onLog: (String) -> Unit = {},
) : ServeSessionFactory {

  override fun create(sessionId: String): ServeSessionState? {
    val rev = sessionId.trim()
    if (rev.isEmpty()) return null
    // worktrees.prepare() enforces the ref allowlist (SECURITY/RCE): it only checks out a revision
    // reachable from an operator-trusted ref, so an arbitrary client-supplied `?session=<rev>` is
    // refused before any checkout. A null here means "not resolvable or not allowed".
    val worktree =
      worktrees.prepare(rev)
        ?: run {
          onLog("serve: revision '$rev' is unavailable (unresolvable or not allowed)")
          return null
        }
    // isSecurityChecked = true: the rev passed the allowlist above, so building it is sanctioned.
    val built =
      builder.build(worktree, module, isSecurityChecked = true)
        ?: run {
          onLog("serve: build/discovery failed for ${module.gradlePath} at '$rev'")
          return null
        }
    return ServeSessionState(
      descriptor = built.descriptor,
      workspaceRoot = built.moduleDir,
      workspaceName = built.moduleDir.name,
      previews = built.previews,
      label = "${module.gradlePath}@$rev",
      declaredThemes = built.declaredThemes,
      // Prune this revision's worktree when the registry reclaims a long-idle suspended forked
      // session (issue #2022). A later `?session=<rev>` for the same revision re-prepares the
      // worktree from the shared object store, so reclaiming is safe.
      reclaim = { worktrees.remove(worktree) },
    )
  }
}
