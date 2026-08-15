package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enumeration coverage for `preview/rows` on the desktop daemon (issue #3749).
 *
 * Row *addressing* shipped first: a caller who already knew a row existed could render it. Nothing
 * could tell them the rows existed, because `previews.json` carries base ids only — discovery reads
 * bytecode and can't instantiate a `PreviewParameterProvider`. [PreviewManifestRouter] can, so it
 * answers the question, and these tests pin both halves of how it does that:
 *
 * 1. **The gate.** A preview that declares no provider returns an empty list from discovery
 *    metadata alone — no `Class.forName`, no provider instantiation. That is what keeps
 *    `preview/rows` cheap enough for a client to call on everything it lists, which is the only way
 *    it's useful.
 * 2. **The ids.** What comes back is `<baseId>_<label>` — exactly the spelling `renderNow` accepts
 *    and the fan-out renderer writes to disk — so a client round-trips them without string surgery.
 *
 * The providers are the `:daemon:desktop` test fixtures, so this is real reflection over real
 * classes rather than a stubbed row list.
 */
class PreviewManifestRowEnumerationTest {

  private fun entry(id: String, provider: String?) =
    PreviewManifestEntry(
      id = id,
      className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
      functionName = "CaseLabelledSquare",
      widthPx = 64,
      heightPx = 64,
      density = 1.0f,
      previewParameterProviderClassName = provider,
    )

  private fun router(vararg entries: PreviewManifestEntry) =
    PreviewManifestRouter(PreviewManifest(previews = entries.toList()))

  /**
   * `CaseTintProvider` yields two values labelled `Dark` and `dark` — case-distinct on purpose, so
   * this also pins that enumeration reports them as the two separate rows they are.
   */
  @Test
  fun `a declared provider enumerates one addressable id per value`() {
    val rows =
      router(entry("Screen", "ee.schimke.composeai.daemon.CaseTintProvider"))
        .previewParameterRows("Screen")

    assertEquals(listOf(0, 1), rows.map { it.index })
    assertEquals(listOf("Dark", "dark"), rows.map { it.label })
    assertEquals(listOf("Screen_Dark", "Screen_dark"), rows.map { it.id })
  }

  /**
   * The metadata gate. Enumerating would throw here — the FQN names no class — so an empty result
   * is positive evidence that the classloader was never touched, not just that nothing was found.
   */
  @Test
  fun `a preview with no provider answers empty without loading anything`() {
    assertEquals(
      emptyList<PreviewParameterRow>(),
      router(entry("Plain", null)).previewParameterRows("Plain"),
    )
    assertEquals(
      emptyList<PreviewParameterRow>(),
      router(entry("Blank", "  ")).previewParameterRows("Blank"),
    )
  }

  /**
   * And the other side of that coin: a declared-but-missing provider does reach the classloader.
   */
  @Test
  fun `a declared provider that does not exist fails loudly`() {
    val failure = runCatching {
      router(entry("Screen", "com.example.NotOnTheClasspath")).previewParameterRows("Screen")
    }
      .exceptionOrNull()
    assertNotNull("a missing provider class must not be swallowed as 'no rows'", failure)
  }

  /**
   * A client holding `Screen_Dark` is asking "what else is there", so the row id resolves to its
   * base and answers with the whole set — rather than making the client strip the suffix itself and
   * re-hit the longest-parameterized-prefix ambiguity `PreviewRowAddress` exists to resolve.
   */
  @Test
  fun `a row-addressed id answers with its siblings, keyed off the base`() {
    val rows =
      router(entry("Screen", "ee.schimke.composeai.daemon.CaseTintProvider"))
        .previewParameterRows("Screen_Dark")

    assertEquals(listOf("Screen_Dark", "Screen_dark"), rows.map { it.id })
  }

  /**
   * Review follow-up: the implementation must live on [DesktopHost], not on the router.
   * [PreviewManifestRouter] is harness-only (`-Dcomposeai.harness.previewsManifest`); every
   * production desktop launch mounts a plain [DesktopHost] with a `PreviewIndex`-backed resolver
   * (`DaemonMain`). A router-only override answered `MethodNotFound` on the path real users take —
   * and the harness tests couldn't see it, because the harness is exactly the path that *does*
   * mount a router.
   */
  @Test
  fun `a plain DesktopHost with a resolver enumerates too`() {
    val spec =
      RenderSpec(
        className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
        functionName = "CaseLabelledSquare",
        previewParameterProviderClassName = "ee.schimke.composeai.daemon.CaseTintProvider",
      )
    val host = DesktopHost(previewSpecResolver = { id -> spec.takeIf { id == "Screen" } })

    assertEquals(
      listOf("Screen_Dark", "Screen_dark"),
      host.previewParameterRows("Screen").map { it.id },
    )
  }

  /**
   * No resolver at all is the one case that genuinely can't enumerate — MethodNotFound is right.
   */
  @Test
  fun `a DesktopHost with no resolver reports the capability as unsupported`() {
    val failure = runCatching { DesktopHost().previewParameterRows("Screen") }.exceptionOrNull()
    assertTrue(
      "expected UnsupportedOperationException, got $failure",
      failure is UnsupportedOperationException,
    )
  }

  @Test
  fun `an unknown previewId is rejected as an argument error`() {
    val failure = runCatching {
      router(entry("Screen", null)).previewParameterRows("Nope")
    }
      .exceptionOrNull()
    assertTrue(
      "expected IllegalArgumentException naming the id, got $failure",
      failure is IllegalArgumentException && failure.message?.contains("Nope") == true,
    )
  }
}
