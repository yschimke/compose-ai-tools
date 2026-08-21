package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeCatalogRevisionTest {

  @Test
  fun `a full-sha raw URL is commit-pinned`() {
    assertTrue(
      ServeCatalogRevision.isCommitPinned(
        "https://raw.githubusercontent.com/o/r/${"a".repeat(40)}/images/button/ideal.png"
      )
    )
  }

  @Test
  fun `a branch ref is not commit-pinned`() {
    // The admission rule the asset cache depends on. `design-artifacts/<system>` is a branch whose
    // name contains a slash, so the ref position reads as `design-artifacts` — not a sha, and not
    // cacheable, which is the whole point: a branch ref moves.
    assertFalse(
      ServeCatalogRevision.isCommitPinned(
        "https://raw.githubusercontent.com/o/r/design-artifacts/compose-m3/catalog.json"
      )
    )
    assertFalse(
      ServeCatalogRevision.isCommitPinned("https://raw.githubusercontent.com/o/r/main/x.png")
    )
  }

  @Test
  fun `an abbreviated sha is not commit-pinned`() {
    // A visitor may type a 7-character pin and `normalize` accepts it, but every URL this server
    // builds for itself carries a resolved head. Admitting both spellings would file one commit's
    // bytes under two cache keys.
    assertFalse(
      ServeCatalogRevision.isCommitPinned("https://raw.githubusercontent.com/o/r/abc1234/x.png")
    )
  }

  @Test
  fun `only the delivery-branch host and a real asset path are commit-pinned`() {
    val sha = "b".repeat(40)
    // Another host that happens to carry a sha-shaped segment is not this cache's business.
    assertFalse(ServeCatalogRevision.isCommitPinned("https://example.com/o/r/$sha/x.png"))
    // A URL naming no asset under the ref addresses nothing worth caching.
    assertFalse(ServeCatalogRevision.isCommitPinned("https://raw.githubusercontent.com/o/r/$sha"))
    assertFalse(ServeCatalogRevision.isCommitPinned("https://raw.githubusercontent.com/o/r/$sha/"))
  }

  @Test
  fun `a pin must be a sha, never a ref`() {
    assertEquals("abc1234", ServeCatalogRevision.normalize("abc1234"))
    assertEquals(
      "46440dd86c24b2da6054ccab587e59fba4b15c7e",
      ServeCatalogRevision.normalize("46440DD86C24B2DA6054CCAB587E59FBA4B15C7E"),
    )
    assertEquals("abc1234", ServeCatalogRevision.normalize("  abc1234  "))
    // The whole point of pinning is that the target cannot move, so every ref-shaped input is
    // refused — including the ones that would resolve perfectly well on raw.githubusercontent.
    assertNull(ServeCatalogRevision.normalize("main"))
    assertNull(ServeCatalogRevision.normalize("design-artifacts/compose-m3"))
    assertNull(ServeCatalogRevision.normalize("refs/heads/main"))
    assertNull(ServeCatalogRevision.normalize("abc123"))
    assertNull(ServeCatalogRevision.normalize("abc1234z"))
    assertNull(ServeCatalogRevision.normalize("../../etc/passwd"))
    assertNull(ServeCatalogRevision.normalize(""))
    assertNull(ServeCatalogRevision.normalize(null))
  }

  @Test
  fun `asset urls are built from validated parts`() {
    assertEquals(
      "https://raw.githubusercontent.com/yschimke/m3-catalog/abc1234/images/navigationbar-short/ideal.png",
      ServeCatalogRevision.assetUrl(
        "yschimke/m3-catalog",
        "abc1234",
        "images/navigationbar-short/ideal.png",
      ),
    )
    // A producer that publishes its rasters somewhere else than the catalogs we happen to ship is
    // still pinnable: the rule is "a relative path to a PNG", not a directory allowlist.
    assertEquals(
      "https://raw.githubusercontent.com/o/r/abc1234/design-references/button.png",
      ServeCatalogRevision.assetUrl("o/r", "abc1234", "design-references/button.png"),
    )
    assertEquals(
      "https://raw.githubusercontent.com/o/r/abc1234/images/a%20b/c%23d.png",
      ServeCatalogRevision.assetUrl("o/r", "abc1234", "images/a b/c#d.png"),
    )
  }

  @Test
  fun `asset urls refuse anything that could redirect the read`() {
    assertNull(ServeCatalogRevision.assetUrl("o/r", "main", "images/a.png"))
    assertNull(ServeCatalogRevision.assetUrl("o/r/extra", "abc1234", "images/a.png"))
    assertNull(ServeCatalogRevision.assetUrl("not-a-repo", "abc1234", "images/a.png"))
    assertNull(ServeCatalogRevision.assetUrl("o/r", "abc1234", "/etc/passwd.png"))
    assertNull(ServeCatalogRevision.assetUrl("o/r", "abc1234", "images/../../../secret.png"))
    assertNull(ServeCatalogRevision.assetUrl("o/r", "abc1234", "https://evil.example/a.png"))
    // Every pinned response is sent as image/png, so a manifest naming something else resolves to
    // no URL rather than to bytes served under the wrong claim.
    assertNull(ServeCatalogRevision.assetUrl("o/r", "abc1234", "liveBundle/app.jar"))
    assertNull(ServeCatalogRevision.assetUrl("o/r", "abc1234", null))
  }

  @Test
  fun `the branch feed url addresses the delivery branch verbatim`() {
    assertEquals(
      "https://github.com/yschimke/m3-catalog/commits/design-artifacts/m3-catalog.atom",
      ServeCatalogRevision.commitsFeedUrl("yschimke/m3-catalog", "design-artifacts/m3-catalog"),
    )
  }

  @Test
  fun `the commit feed parses into revisions, newest first`() {
    val revisions = ServeCatalogRevision.parseCommitsFeed(FEED)

    assertEquals(2, revisions.size)
    assertEquals("46440dd86c24b2da6054ccab587e59fba4b15c7e", revisions[0].commit)
    assertEquals("2026-08-13T09:42:57Z", revisions[0].date)
    // The source sha the publish subject stamps — the change a reader is actually looking for when
    // they go back a version. The delivery sha is only a publish marker.
    assertEquals("0b0c2063", revisions[0].sourceSha)
    assertEquals("46440dd8", revisions[0].short)
    assertEquals("41c7a15fd21f52e7c6a959a0c441eb600ca46d4f", revisions[1].commit)
    assertEquals("b34eff53", revisions[1].sourceSha)
  }

  @Test
  fun `a revision the feed states nothing useful about still lists`() {
    val feed =
      """
      <feed><entry>
        <id>tag:github.com,2008:Grit::Commit/1111111111111111111111111111111111111111</id>
        <title>hand-pushed fixup</title>
        <updated>2026-01-02T03:04:05Z</updated>
      </entry></feed>
      """
        .trimIndent()

    val revision = ServeCatalogRevision.parseCommitsFeed(feed).single()

    assertEquals("1111111111111111111111111111111111111111", revision.commit)
    assertEquals("2026-01-02T03:04:05Z", revision.date)
    // No `regenerate … (<date>, <sha>)` subject to read a source commit out of. The revision is
    // still a real, pinnable publish, so it is offered under its own sha rather than dropped.
    assertNull(revision.sourceSha)
  }

  @Test
  fun `a feed that is not the shape we expect degrades to no revisions`() {
    assertEquals(emptyList(), ServeCatalogRevision.parseCommitsFeed(""))
    assertEquals(emptyList(), ServeCatalogRevision.parseCommitsFeed("<html>404</html>"))
    assertEquals(
      emptyList(),
      ServeCatalogRevision.parseCommitsFeed("<feed><entry><title>no id</title></entry></feed>"),
    )
  }

  @Test
  fun `the revision list is capped`() {
    val entries =
      (0 until 40).joinToString("\n") { i ->
        "<entry><id>tag:github.com,2008:Grit::Commit/${"%040x".format(i)}</id>" +
          "<updated>2026-01-01T00:00:00Z</updated></entry>"
      }

    val revisions = ServeCatalogRevision.parseCommitsFeed("<feed>$entries</feed>")

    assertEquals(ServeCatalogRevision.MAX_REVISIONS, revisions.size)
    assertEquals("%040x".format(0), revisions.first().commit)
  }

  @Test
  fun `a pinned sha links to its tree on GitHub`() {
    assertEquals(
      "https://github.com/o/r/tree/abc1234",
      ServeCatalogRevision.treeUrl("o/r", "abc1234"),
    )
    assertNull(ServeCatalogRevision.treeUrl("o/r", "main"))
    assertNull(ServeCatalogRevision.treeUrl(null, "abc1234"))
  }

  @Test
  fun `a path-scoped feed URL names the branch and the render`() {
    assertEquals(
      "https://github.com/yschimke/wear-m3-catalog/commits/design-artifacts/wear-m3-catalog/" +
        "images/media-playerscreen/ideal__default__192dp.png.atom",
      ServeCatalogRevision.pathCommitsFeedUrl(
        "yschimke/wear-m3-catalog",
        "design-artifacts/wear-m3-catalog",
        "images/media-playerscreen/ideal__default__192dp.png",
      ),
    )
  }

  @Test
  fun `a path-scoped feed URL refuses what it cannot address`() {
    val repo = "o/r"
    val branch = "design-artifacts/x"
    // Not a PNG, absolute, traversing, and a repo that isn't one — the same rules `assetUrl`
    // applies, because the path arrives from the same untrusted place: a published manifest.
    assertNull(ServeCatalogRevision.pathCommitsFeedUrl(repo, branch, "images/a/b.json"))
    assertNull(ServeCatalogRevision.pathCommitsFeedUrl(repo, branch, "/images/a/b.png"))
    assertNull(ServeCatalogRevision.pathCommitsFeedUrl(repo, branch, "images/../../x.png"))
    assertNull(ServeCatalogRevision.pathCommitsFeedUrl(repo, branch, null))
    assertNull(ServeCatalogRevision.pathCommitsFeedUrl("not-a-repo", branch, "a/b.png"))
    assertNull(ServeCatalogRevision.pathCommitsFeedUrl(repo, "..", "a/b.png"))
  }

  /**
   * The shape that prompted this feature: twelve publishes of
   * `media-playerscreen__ideal__default__192dp`, of which exactly one moved a pixel.
   */
  @Test
  fun `runs collapse consecutive publishes that share their bytes`() {
    val revisions = revisions(12)
    // The render changed at r1 (the footer buttons) and at r11 (where it was first published).
    val runs = ServeCatalogRevision.renderRuns(revisions, setOf(sha(1), sha(11)))
    assertEquals(
      listOf(
        ServeCatalogRevision.RenderRun(head = sha(0), commits = 2, open = false),
        ServeCatalogRevision.RenderRun(head = sha(2), commits = 10, open = false),
      ),
      runs,
    )
  }

  @Test
  fun `a run the window cuts off is reported as open`() {
    // Same list, but the older boundary has scrolled out of the twelve-publish window: the second
    // run is at *least* ten publishes and must not claim to be exactly ten.
    val runs = ServeCatalogRevision.renderRuns(revisions(12), setOf(sha(1)))
    assertEquals(sha(2), runs[1].head)
    assertEquals(10, runs[1].commits)
    assertTrue(runs[1].open)
    assertFalse(runs[0].open)
  }

  @Test
  fun `a render nothing touched is one open run`() {
    val runs = ServeCatalogRevision.renderRuns(revisions(12), emptySet())
    assertEquals(1, runs.size)
    assertEquals(sha(0), runs[0].head)
    assertEquals(12, runs[0].commits)
    assertTrue(runs[0].open)
  }

  @Test
  fun `a render that changed every publish is all singletons`() {
    val revisions = revisions(4)
    val runs = ServeCatalogRevision.renderRuns(revisions, revisions.map { it.commit }.toSet())
    assertEquals(listOf(sha(0), sha(1), sha(2), sha(3)), runs.map { it.head })
    assertTrue(runs.all { it.commits == 1 && !it.open })
  }

  @Test
  fun `no revisions means no runs`() {
    assertEquals(
      emptyList<ServeCatalogRevision.RenderRun>(),
      ServeCatalogRevision.renderRuns(emptyList(), setOf(sha(0))),
    )
  }

  /** `revisions(3)` = three publishes, newest first, with predictable shas. */
  private fun revisions(count: Int): List<ServeCatalogRevision.Revision> =
    (0 until count).map { ServeCatalogRevision.Revision(commit = sha(it), date = "2026-08-20") }

  private fun sha(index: Int): String = index.toString().padStart(40, '0')

  /** Two entries as GitHub actually serves them, trimmed of the fields nothing here reads. */
  private val FEED =
    """
    <?xml version="1.0" encoding="UTF-8"?>
    <feed xmlns="http://www.w3.org/2005/Atom" xml:lang="en-US">
      <title>Recent Commits to compose-ai-tools:design-artifacts/compose-m3</title>
      <updated>2026-08-13T09:42:57Z</updated>
      <entry>
        <id>tag:github.com,2008:Grit::Commit/46440dd86c24b2da6054ccab587e59fba4b15c7e</id>
        <title>
            chore(design-artifacts): regenerate compose-m3 catalog (2026-08-13, 0…
        </title>
        <updated>2026-08-13T09:42:57Z</updated>
        <content type="html">
          &lt;pre&gt;chore(design-artifacts): regenerate compose-m3 catalog (2026-08-13, 0b0c2063)&lt;/pre&gt;
        </content>
      </entry>
      <entry>
        <id>tag:github.com,2008:Grit::Commit/41c7a15fd21f52e7c6a959a0c441eb600ca46d4f</id>
        <updated>2026-08-13T07:10:54Z</updated>
        <content type="html">
          &lt;pre&gt;chore(design-artifacts): regenerate compose-m3 catalog (2026-08-13, b34eff53)&lt;/pre&gt;
        </content>
      </entry>
    </feed>
    """
      .trimIndent()
}
