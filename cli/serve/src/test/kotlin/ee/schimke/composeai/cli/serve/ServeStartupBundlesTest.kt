package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleVerifier
import ee.schimke.composeai.bundle.TrustStore
import ee.schimke.composeai.bundle.TrustedBranch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServeStartupBundlesTest {

  @Test
  fun `bare url spec derives the session name from the file basename`() {
    val specs =
      ServeStartupBundles.parse(
        listOf("https://raw.githubusercontent.com/o/r/main/bundle/compose-m3.bundle")
      )
    assertEquals(1, specs.size)
    assertEquals("compose-m3", specs.single().name)
    assertEquals(
      "https://raw.githubusercontent.com/o/r/main/bundle/compose-m3.bundle",
      specs.single().source,
    )
  }

  @Test
  fun `bare path spec strips a known extension for the name`() {
    assertEquals("demo", ServeStartupBundles.parse(listOf("/tmp/demo.bundle")).single().name)
    assertEquals("demo", ServeStartupBundles.parse(listOf("/tmp/demo.png")).single().name)
    assertEquals("demo", ServeStartupBundles.parse(listOf("/tmp/demo.zip")).single().name)
  }

  @Test
  fun `explicit name=source form sets the name`() {
    val spec = ServeStartupBundles.parse(listOf("mine=https://host/x/y/z.bundle")).single()
    assertEquals("mine", spec.name)
    assertEquals("https://host/x/y/z.bundle", spec.source)
  }

  @Test
  fun `a url with a query is not mis-split on its equals sign`() {
    // The `=` lives in the query, not a `name=` prefix — the whole URL must stay the source.
    val spec = ServeStartupBundles.parse(listOf("https://host/b.bundle?token=abc")).single()
    assertEquals("https://host/b.bundle?token=abc", spec.source)
    assertEquals("b", spec.name)
  }

  @Test
  fun `an unusable name prefix falls back to treating the whole entry as a source path`() {
    // "§§§" fails the name charset, so the entry is NOT split on `=`; the whole string is the
    // source and the name is derived from its basename ("x").
    val specs = ServeStartupBundles.parse(listOf("§§§=/tmp/x.bundle"))
    assertEquals("x", specs.single().name)
    assertEquals("§§§=/tmp/x.bundle", specs.single().source)
  }

  @Test
  fun `isUrl distinguishes http(s) from local paths`() {
    assertTrue(ServeStartupBundles.isUrl("https://host/x.bundle"))
    assertTrue(ServeStartupBundles.isUrl("http://host/x.bundle"))
    assertFalse(ServeStartupBundles.isUrl("/tmp/x.bundle"))
    assertFalse(ServeStartupBundles.isUrl("./rel/x.bundle"))
  }

  @Test
  fun `candidate origins are enumerated only for a raw githubusercontent url`() {
    val origins =
      ServeStartupBundles.candidateOrigins(
        "https://raw.githubusercontent.com/o/r/main/bundle/app.bundle"
      )
    // ref splits leaving ≥1 path segment: "main", "main/bundle".
    assertEquals(listOf("o/r"), origins.map { it.repo }.distinct())
    assertEquals(listOf("main", "main/bundle"), origins.map { it.branch })
  }

  @Test
  fun `candidate origins is empty for a non-github host or a local path`() {
    assertTrue(
      ServeStartupBundles.candidateOrigins("https://example.com/o/r/main/app.bundle").isEmpty()
    )
    assertTrue(ServeStartupBundles.candidateOrigins("/tmp/app.bundle").isEmpty())
    assertTrue(
      ServeStartupBundles.candidateOrigins("https://raw.githubusercontent.com/only/two").isEmpty()
    )
  }

  @Test
  fun `a slash-containing branch matches a branch glob in the trust store`() {
    // Regression: design-artifacts URLs are published from branches like
    // `design-artifacts/compose-m3`
    // (the branch name itself has a slash). The trust store globs are `design-artifacts/*`, so the
    // caller must be able to pick the two-segment branch, not the bare `design-artifacts`.
    val trust =
      TrustStore(
        branches =
          listOf(TrustedBranch(repo = "yschimke/compose-ai-tools", branch = "design-artifacts/*"))
      )
    val origins =
      ServeStartupBundles.candidateOrigins(
        "https://raw.githubusercontent.com/yschimke/compose-ai-tools/design-artifacts/compose-m3/bundle/app.bundle"
      )
    // The caller's selection: first candidate the trust store trusts.
    val trusted = origins.firstOrNull { trust.trustsBranch(it.repo, it.branch) }
    assertEquals("design-artifacts/compose-m3", trusted?.branch)
    assertEquals("yschimke/compose-ai-tools", trusted?.repo)
  }

  @Test
  fun `a branch origin badges a fetched bundle Trusted(Branch) without a signature`() {
    // A bundle pulled from a branch in the trust store is trusted-by-origin even unsigned — the
    // gate the startup --bundle live path reuses. Verify the verdict the branch origin produces.
    val trust = TrustStore(branches = listOf(TrustedBranch(repo = "o/r", branch = "*")))
    val origin =
      ServeStartupBundles.candidateOrigins(
          "https://raw.githubusercontent.com/o/r/main/b/app.bundle"
        )
        .first { trust.trustsBranch(it.repo, it.branch) }
    // An unsigned bundle (no signatures.json); origin alone must still make it Trusted (branch
    // basis), which is what unlocks the live lane for a branch-fetched bundle.
    val bundle = ServeBundle.zip(linkedMapOf("previews/x.png" to byteArrayOf(1)))
    val verdict = BundleVerifier.verify(bundle, trust, origin)
    assertTrue(verdict is BundleVerifier.Verdict.Trusted)
    assertEquals("branch:o/r@main", BundleVerifier.summary(verdict))
  }

  @Test
  fun `without a matching trusted branch a fetched bundle stays Unverified`() {
    val origin =
      ServeStartupBundles.candidateOrigins(
          "https://raw.githubusercontent.com/o/r/main/b/app.bundle"
        )
        .first()
    val bundle = ServeBundle.zip(linkedMapOf("previews/x.png" to byteArrayOf(1)))
    val verdict = BundleVerifier.verify(bundle, TrustStore.EMPTY, origin)
    assertTrue(verdict is BundleVerifier.Verdict.Unverified)
  }
}
