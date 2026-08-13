package ee.schimke.composeai.cli.serve

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

  /** A branch path is pinnable when it is a relative, traversal-free path to a PNG. */
  fun normalizePath(path: String?): String? {
    val p = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (p.startsWith("/") || p.contains("://") || !p.endsWith(PINNABLE_SUFFIX)) return null
    val segments = p.split('/')
    if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
    return p
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
