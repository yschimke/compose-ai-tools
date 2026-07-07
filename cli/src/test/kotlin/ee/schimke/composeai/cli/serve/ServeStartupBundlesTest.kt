package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
  fun `origin is derived only from a raw githubusercontent url`() {
    val origin =
      ServeStartupBundles.originOf(
        "https://raw.githubusercontent.com/yschimke/compose-ai-tools/design-artifacts/compose-m3/bundle/app.bundle"
      )
    assertEquals("yschimke/compose-ai-tools", origin?.repo)
    assertEquals("design-artifacts", origin?.branch)
  }

  @Test
  fun `origin is null for a non-github host or a local path`() {
    assertNull(ServeStartupBundles.originOf("https://example.com/o/r/main/app.bundle"))
    assertNull(ServeStartupBundles.originOf("/tmp/app.bundle"))
    assertNull(ServeStartupBundles.originOf("https://raw.githubusercontent.com/only/two"))
  }

  @Test
  fun `a branch origin badges a fetched bundle Trusted(Branch) without a signature`() {
    // A bundle pulled from a branch in the trust store is trusted-by-origin even unsigned — the
    // gate the startup --bundle live path reuses. Verify the verdict the branch origin produces.
    val trust = TrustStore(branches = listOf(TrustedBranch(repo = "o/r", branch = "*")))
    val origin =
      ServeStartupBundles.originOf("https://raw.githubusercontent.com/o/r/main/b/app.bundle")!!
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
      ServeStartupBundles.originOf("https://raw.githubusercontent.com/o/r/main/b/app.bundle")!!
    val bundle = ServeBundle.zip(linkedMapOf("previews/x.png" to byteArrayOf(1)))
    val verdict = BundleVerifier.verify(bundle, TrustStore.EMPTY, origin)
    assertTrue(verdict is BundleVerifier.Verdict.Unverified)
  }
}
