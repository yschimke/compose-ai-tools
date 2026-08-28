package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.web.WebEscaping

/**
 * **Historical permalinks**: pinning a served catalog page to one delivery-branch commit.
 *
 * A published catalog URL — `/m3-catalog/compare/navigationbar-short__…?reference=…` — names a
 * preview, not a version of it. The `design-artifacts/<system>` branch is regenerated on every
 * catalog change and each regeneration is a commit on the branch tip ([Delivery-branch
 * history][docs/design/DESIGN_CATALOGS.md]), so the id is stable while the pixels behind it move.
 * Anyone linking to a render — in an issue, a review, a design doc — is linking to whatever that
 * preview looks like when the link is *opened*, which is exactly the thing a link is supposed to
 * defend against (issue #3723).
 *
 * The fix needs no new publishing: the versions are already on the branch. A commit sha plus the
 * asset's branch path addresses the published bytes exactly, and `raw.githubusercontent.com` serves
 * any commit, not just a branch name. So a permalink is the page URL plus [PARAM]`=<sha>`, and the
 * asset lanes answer it out of the branch at that commit rather than out of the catalog on disk.
 *
 * Everything here is pure — sha and path validation, URL assembly, and the parse that turns the
 * branch's commit feed into a list of publishes — so the rules are unit-testable without a network
 * or a repository. The fetching itself stays in [ServeCatalogStore], which owns the network policy
 * for the delivery branch.
 */
object ServeCatalogRevision {

  /** Query parameter that pins a page (and every asset it links) to one delivery-branch commit. */
  const val PARAM: String = "at"

  /** How much of a sha is shown in the UI — enough to be unambiguous, short enough to read. */
  const val SHORT_LENGTH: Int = 8

  /**
   * A commit sha as this feature accepts it: 7–40 lowercase hex, never a ref name.
   *
   * Refusing refs is the load-bearing half. A pin is served by fetching
   * `raw.githubusercontent.com/<repo>/<pin>/<path>`, and that path component accepts a *branch*
   * just as happily as a sha — so admitting `main`, or `refs/heads/…`, would turn a
   * visitor-supplied string into a choice of which tree the server reads. It would also quietly
   * break the promise the feature exists to make: a branch name is precisely the moving target a
   * permalink replaces.
   */
  private val COMMIT = Regex("[0-9a-f]{7,40}")

  /** `owner/name`, matching the shape a GitHub repo coordinate can take and nothing else. */
  private val REPO = Regex("[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*")

  /**
   * What a pinned read may name: a **PNG**, at a relative, traversal-free path.
   *
   * Deliberately a file-kind rule rather than a directory allowlist. The paths here come from the
   * catalog's own manifests, and producers choose their own layout — the published catalogs use
   * `images/` and `references/`, but nothing stops a producer from publishing under
   * `design-references/`, and pinning must not quietly stop working for them. What the rule does
   * guard is the pin serving something that isn't the inert image the lane claims to serve: every
   * pinned response is sent as `image/png`, so a garbled manifest naming anything else on the
   * branch resolves to no URL at all.
   */
  private const val PINNABLE_SUFFIX = ".png"

  /**
   * Normalize a request-supplied pin to a canonical sha, or null when it isn't one.
   *
   * Case-folded rather than rejected, because git prints shas in both cases and someone will paste
   * one from a UI that upper-cases them; the canonical lowercase form is what everything downstream
   * (cache keys, URLs, the displayed short sha) then agrees on.
   */
  fun normalize(raw: String?): String? {
    val trimmed = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return trimmed.takeIf { COMMIT.matches(it) }
  }

  /** The sha as shown on a pinned page's banner. */
  fun short(commit: String): String = commit.take(SHORT_LENGTH)

  /**
   * `raw.githubusercontent.com/<repo>/<commit>/<path>` for one published asset, or null when any
   * part fails validation.
   *
   * [path] must satisfy [normalizePath], so a garbled catalog manifest cannot widen a pinned read
   * into an arbitrary file on the branch. Each segment is percent-encoded, leaving the `/`
   * structure intact.
   */
  fun assetUrl(repo: String?, commit: String?, path: String?): String? {
    val r = repo?.trim()?.trim('/')?.takeIf { REPO.matches(it) } ?: return null
    val c = normalize(commit) ?: return null
    val p = normalizePath(path) ?: return null
    val encoded = p.split('/').joinToString("/") { WebEscaping.urlEncodeSegment(it) }
    return "https://raw.githubusercontent.com/$r/$c/$encoded"
  }

  /**
   * Whether [url] addresses **one immutable commit** on the delivery-branch host, rather than a
   * moving branch ref.
   *
   * This is the admission rule for caching a fetched asset ([CatalogBlobPool]), and it lives here
   * because this object already owns the question of what counts as a commit — [COMMIT] is the same
   * regex a request-supplied `?at=` pin is validated against. A second spelling of "is this a sha"
   * elsewhere is how the two would eventually disagree, and a disagreement in the permissive
   * direction caches a branch ref, which is precisely the moving target the cache must never key
   * on.
   *
   * Deliberately shaped as "recognise the exact URL form we build" rather than "parse any URL":
   * everything cacheable is assembled by [assetUrl] or by the store's `base + path`, both of which
   * are `https://raw.githubusercontent.com/<owner>/<repo>/<ref>/<path…>`. Anything that does not
   * match that shape — a different host, a short ref, a missing path — is simply not cached, which
   * costs a re-fetch and never a wrong answer.
   */
  fun isCommitPinned(url: String): Boolean {
    val rest = url.removePrefix(RAW_HOST_PREFIX)
    if (rest.length == url.length) return false
    val segments = rest.split('/')
    // owner, repo, ref, and at least one NON-EMPTY path segment — a URL with nothing after the ref
    // (with or without a trailing slash) addresses no asset, so there is nothing to cache under it
    // either.
    if (segments.size < 4 || segments.drop(3).all { it.isEmpty() }) return false
    // The full 40 hex characters, not the 7–40 a visitor may type: every URL this server builds
    // for itself carries a resolved head, and accepting an abbreviation here would let two
    // spellings of one commit occupy two cache entries.
    return segments[2].length == FULL_COMMIT_LENGTH && COMMIT.matches(segments[2])
  }

  /** The host and scheme every delivery-branch read goes through. */
  private const val RAW_HOST_PREFIX = "https://raw.githubusercontent.com/"

  /** A resolved commit sha, in full. */
  private const val FULL_COMMIT_LENGTH = 40

  /** A branch path is pinnable when it is a relative, traversal-free path to a PNG. */
  fun normalizePath(path: String?): String? {
    val p = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (p.startsWith("/") || p.contains("://") || !p.endsWith(PINNABLE_SUFFIX)) return null
    val segments = p.split('/')
    if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
    return p
  }

  /** The catalog manifest, as published on a delivery branch. */
  const val CATALOG_FILE: String = "catalog.json"

  /** The design-reference manifest, as published on a delivery branch. */
  const val REFERENCES_FILE: String = "references/index.json"

  /**
   * URL for one of the two **manifests** a pinned read resolves paths from ([ServePinnedManifest]).
   *
   * Separate from [assetUrl] rather than a relaxation of it, and the separation is the safety
   * argument: [assetUrl] takes a path out of a manifest and so must assume the string is untrusted,
   * while this takes one of exactly two names this codebase declares. Anything else resolves to no
   * URL, so widening the pinned lane to JSON cannot widen what it can be pointed at.
   */
  fun manifestUrl(repo: String?, commit: String?, file: String?): String? {
    val r = repo?.trim()?.trim('/')?.takeIf { REPO.matches(it) } ?: return null
    val c = normalize(commit) ?: return null
    val f = file?.takeIf { it == CATALOG_FILE || it == REFERENCES_FILE } ?: return null
    return "https://raw.githubusercontent.com/$r/$c/$f"
  }

  /** GitHub's tree view for a pinned revision — where the sha on a banner links to. */
  fun treeUrl(repo: String?, commit: String?): String? {
    val r = repo?.trim()?.trim('/')?.takeIf { REPO.matches(it) } ?: return null
    val c = normalize(commit) ?: return null
    return "https://github.com/$r/tree/$c"
  }

  /** One published revision of a catalog — a commit on its delivery branch. */
  data class Revision(
    /** Delivery-branch commit sha, full length. */
    val commit: String,
    /** When it was published, ISO-8601 as the feed states it. */
    val date: String,
    /**
     * The source commit this catalog was regenerated from, recovered from the publish subject
     * (`chore(design-artifacts): regenerate <system> catalog (<date>, <sha>)`). Null when the
     * subject doesn't carry one — an older publish, or a hand-pushed commit.
     */
    val sourceSha: String? = null,
  ) {
    val short: String
      get() = short(commit)
  }

  /**
   * The branch's **commit feed** — the list of published revisions, newest first.
   *
   * GitHub serves a branch's history as Atom at `commits/<branch>.atom`, unauthenticated and
   * unmetered. That matters: the obvious alternative, `api.github.com/repos/<repo>/commits`, spends
   * one of 60 unauthenticated calls an hour per IP, which a box serving twenty catalogs on an
   * hourly refresh would exhaust before lunch. One small response per catalog load carries
   * everything this feature needs — the tip to mint a permalink from, and the recent revisions to
   * offer as destinations.
   *
   * Branch names carry a `/` (`design-artifacts/compose-m3`) and the feed path takes it verbatim,
   * exactly as GitHub's own tree URLs do.
   */
  fun commitsFeedUrl(repo: String, branch: String): String =
    "https://github.com/$repo/commits/$branch.atom"

  /**
   * [commitsFeedUrl] narrowed to **one path** — the publishes in which those bytes actually
   * changed, which is a very different list from the branch's.
   *
   * This is the whole substrate of the render-run markers. A delivery branch is regenerated on
   * every catalog change, so most publishes rewrite nothing a given preview can see: the media
   * player above went ten consecutive publishes without a pixel moving. Git already knows that — a
   * path-scoped log reports only the commits that touched the file — so the collapse costs one feed
   * read rather than downloading a dozen PNGs and hashing them.
   *
   * Crucially it is the **same** unmetered Atom surface [commitsFeedUrl] uses, and the same
   * `Grit::Commit/<sha>` shape, so [parseCommitsFeed] reads it verbatim. The API spelling
   * (`api.github.com/repos/<repo>/commits?path=…`) returns the identical answer and was rejected
   * for the identical reason: it costs one of 60 unauthenticated calls an hour, and this lane is
   * *per preview* rather than per catalog load, so it would exhaust the budget on a single page.
   *
   * [path] goes through [normalizePath] and is then re-encoded segment by segment, because unlike
   * the branch — a name this server configures — it comes out of a published catalog manifest. A
   * path that cannot be normalized yields no URL, so a garbled manifest cannot point the feed read
   * at something else on the branch.
   */
  fun pathCommitsFeedUrl(repo: String, branch: String, path: String?): String? {
    val r = repo.trim().trim('/').takeIf { REPO.matches(it) } ?: return null
    val b = branch.trim().trim('/').takeIf { it.isNotEmpty() && !it.contains("..") } ?: return null
    val p = normalizePath(path) ?: return null
    val encoded = p.split('/').joinToString("/") { WebEscaping.urlEncodeSegment(it) }
    return "https://github.com/$r/commits/$b/$encoded.atom"
  }

  /**
   * How many path-scoped publishes to keep. The feed itself returns about twenty, and only those
   * falling inside the page's [MAX_REVISIONS] window can mark anything — but parsing the whole
   * response costs nothing and keeping the surplus means a run whose boundary sits just outside the
   * window is still recognised as closed rather than reported as open.
   */
  const val MAX_PATH_REVISIONS: Int = 40

  /**
   * One stretch of consecutive publishes that all carry the **same** render bytes.
   *
   * The unit the viewer draws a thumbnail for: a run is one distinct look, so a menu showing one
   * thumbnail per run answers "which of these twelve actually differ?" at a glance instead of
   * making a reader open them one at a time.
   */
  data class RenderRun(
    /**
     * The **newest** publish carrying these bytes — the top row of the run as the menu lists it,
     * newest first.
     *
     * Deliberately the newest rather than the publish that *introduced* the look. Both are honest
     * answers and the manifest models both ([PreviewHistoryManifest.ManifestVersion.commit] versus
     * `sinceCommit`), but a thumbnail is an anchor for the rows beneath it: at the head it reads
     * "this look holds from here down", while at the introducing commit it would sit at the
     * *bottom* of the stretch it describes and every row above it would look unlabelled.
     */
    val head: String,
    /** Publishes in this run, within the window it was computed over. */
    val commits: Int,
    /**
     * True when the run was cut off by the end of the window rather than by a real change — the
     * bytes may well go back further. Without it a run closed by the window would claim a publish
     * count it cannot support, which is the one thing a "these are identical" marker must not do.
     */
    val open: Boolean = false,
  )

  /**
   * Collapse [revisions] (newest first) into [RenderRun]s, given the publishes in which the render
   * changed ([pathCommitsFeedUrl]).
   *
   * The rule is one line and the off-by-one in it is the whole function: a commit in [changedAt] is
   * where the bytes **became** what they are, so it *ends* the run it belongs to and the row after
   * it begins the next. Reading the boundary as "starts a run" instead puts every thumbnail one row
   * too low.
   *
   * [revisions] must be the same list the menu renders — already narrowed to the publishes that
   * contain this preview — so that heads name rows a reader can actually see. A change point for a
   * publish that was filtered out simply never matches, which is the correct outcome: the row it
   * would have closed is not on screen either.
   *
   * ### Known limitation: a render removed and re-added
   *
   * [changedAt] says a publish **touched** the path, not that its bytes differ from the previous
   * *visible* revision. Normally those coincide, because git is content-addressed: rewriting a file
   * with identical bytes leaves the tree entry alone, so no commit reports it. The one case where
   * they diverge is a preview dropped from a publish and restored later — git records both the
   * deletion and the re-addition, the deletion's publish is filtered out of [revisions] as one that
   * did not contain the preview, and the re-addition is read here as a boundary. If the restored
   * PNG is byte-identical to the one before the gap, that stretch is reported as two runs and the
   * menu draws two identical thumbnails with a rule between them.
   *
   * Left as-is deliberately. Closing it means comparing the actual bytes at such a boundary, which
   * costs the two image reads this whole approach exists to avoid, for a case that needs a preview
   * to leave the catalog and come back unchanged. The failure is also self-evident rather than
   * misleading about provenance: each thumbnail really is that revision's render, and a reader sees
   * two pictures that match. Only the "N distinct renders" count is overstated.
   */
  fun renderRuns(
    revisions: List<Revision>,
    changedAt: Set<String>,
  ): List<RenderRun> {
    if (revisions.isEmpty()) return emptyList()
    val runs = mutableListOf<RenderRun>()
    var head = 0
    revisions.forEachIndexed { index, revision ->
      val boundary = revision.commit in changedAt
      val last = index == revisions.lastIndex
      if (!boundary && !last) return@forEachIndexed
      runs +=
        RenderRun(
          head = revisions[head].commit,
          commits = index - head + 1,
          // Closed by running out of rows rather than by a change: the look predates the window.
          open = last && !boundary,
        )
      head = index + 1
    }
    return runs
  }

  /**
   * Parse [commitsFeedUrl]'s response into revisions, newest first.
   *
   * Deliberately a shape match rather than an XML parse. The two fields that matter are already
   * unambiguous in the document — a commit id appears exactly once per entry as
   * `Grit::Commit/<sha>`, and `<updated>` is the entry's publish time — so scanning for them costs
   * no parser, no entity decoding, and no exposure to whatever a feed grows next. Anything that
   * doesn't match the expected shape is skipped, which is the right failure mode for a document
   * this server neither owns nor versions: a changed feed degrades to fewer (or no) revisions,
   * never to a broken catalog.
   *
   * [limit] caps how many are kept. The feed itself returns about twenty; a page offering more than
   * a handful of "go back to" destinations is a log, not a control.
   */
  fun parseCommitsFeed(xml: String, limit: Int = MAX_REVISIONS): List<Revision> =
    ENTRY.findAll(xml)
      .mapNotNull { entry ->
        val body = entry.value
        val commit = COMMIT_ID.find(body)?.groupValues?.get(1) ?: return@mapNotNull null
        val date = UPDATED.find(body)?.groupValues?.get(1)?.trim().orEmpty()
        Revision(
          commit = commit,
          date = date,
          // The publish subject stamps the source commit the catalog was regenerated from, which is
          // far more useful to a human than the delivery-branch sha — that one is only a publish
          // marker. Same join [PreviewHistory] makes on the baseline branches, and the same
          // tolerance: a subject that doesn't carry one simply has none.
          sourceSha = SOURCE_SHA.find(body)?.groupValues?.get(1),
        )
      }
      .take(limit)
      .toList()

  /**
   * How many published revisions a page offers. The delivery branches are regenerated on every
   * catalog change (several times a day on an active system), so this is roughly the last week of
   * publishes — enough to reach "the one from before that PR" without turning a control into a
   * changelog.
   */
  const val MAX_REVISIONS: Int = 12

  private val ENTRY = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
  private val COMMIT_ID = Regex("Grit::Commit/([0-9a-f]{40})")
  private val UPDATED = Regex("<updated>([^<]{1,64})</updated>")

  /**
   * The `(<date>, <sha>)` tail of a regenerate subject; 7–40 hex so a full-sha stamp still parses.
   */
  private val SOURCE_SHA = Regex("catalog \\([^)]*?,\\s*([0-9a-f]{7,40})\\)")
}
