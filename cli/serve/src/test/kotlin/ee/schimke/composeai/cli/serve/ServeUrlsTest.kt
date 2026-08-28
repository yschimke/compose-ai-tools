package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ServeUrlsTest {

  @Test
  fun `generated tokens are url-safe and unique`() {
    val a = ServeUrls.generateToken()
    val b = ServeUrls.generateToken()
    assertNotEquals(a, b)
    assertTrue(a.isNotEmpty())
    // base64url alphabet only, no padding.
    assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' }, "unexpected char in '$a'")
  }

  @Test
  fun `token match is exact and rejects null or wrong`() {
    val token = "s3cr3t-token_value"
    assertTrue(ServeUrls.tokensMatch(token, token))
    assertFalse(ServeUrls.tokensMatch(token, null))
    assertFalse(ServeUrls.tokensMatch(token, ""))
    assertFalse(ServeUrls.tokensMatch(token, "s3cr3t-token_valu"))
    assertFalse(ServeUrls.tokensMatch(token, "$token-extra"))
  }

  @Test
  fun `isExposed only for wildcard binds`() {
    assertTrue(ServeUrls.isExposed("0.0.0.0"))
    assertTrue(ServeUrls.isExposed("::"))
    assertFalse(ServeUrls.isExposed("127.0.0.1"))
    assertFalse(ServeUrls.isExposed("192.168.1.5"))
  }

  @Test
  fun `urls carry the token and percent-encode the preview id`() {
    val origin = ServeUrls.origin("127.0.0.1", 8723)
    assertEquals("http://127.0.0.1:8723", origin)

    val landing = ServeUrls.landingUrl(origin, "tok en")
    assertTrue(landing.startsWith("http://127.0.0.1:8723/?token="))
    assertTrue("tok%20en" in landing, "token should be percent-encoded: $landing")

    // A preview id with characters that must not survive raw in a URL.
    val viewer = ServeUrls.viewerUrl(origin, "com.x.Foo#bar baz", "tok")
    assertTrue("/p/com.x.Foo%23bar%20baz?token=tok" in viewer, viewer)

    val render =
      ServeUrls.renderUrl(origin, "com.x.Foo", "tok", mapOf("uiMode" to "dark", "device" to ""))
    assertTrue(render.startsWith("http://127.0.0.1:8723/render/com.x.Foo.png?token=tok"), render)
    assertTrue("uiMode=dark" in render, render)
    // Blank override values are dropped.
    assertFalse("device=" in render, render)
  }

  @Test
  fun `wasm app src strips the variant to the component slug but bakes its theme`() {
    // The Wasm registry keys by component slug, so the variant is dropped from `id` — but the
    // variant's theme rides along as uiMode so the in-browser app opens on the same theme as the
    // baked snapshot (the app itself defaults to light).
    assertEquals(
      "/wasm/compose-m3/?id=button-filled&uiMode=dark",
      ServeUrls.wasmAppSrc("compose-m3", "button-filled__ideal__default__dark"),
    )
    assertEquals(
      "/wasm/compose-m3/?id=button-filled&uiMode=light",
      ServeUrls.wasmAppSrc("compose-m3", "button-filled__ideal__default__light"),
    )
    // No theme axis → no uiMode forced (the app uses its own default).
    assertEquals("/wasm/wear-m3/?id=chip", ServeUrls.wasmAppSrc("wear-m3", "chip__compact"))
    // A bare component id (no variant) is passed through unchanged.
    assertEquals("/wasm/compose-m3/?id=switch", ServeUrls.wasmAppSrc("compose-m3", "switch"))
  }

  @Test
  fun `private wasm app src keeps the token in the inherited directory path`() {
    assertEquals(
      "/wasm-private/secret/local%3Aui/?id=button&uiMode=dark",
      ServeUrls.privateWasmAppSrc("local:ui", "button__dark", "secret"),
    )
  }

  @Test
  fun `github blob url joins repo ref module and source path, per-segment encoded`() {
    // The module (catalog source subdir) is prefixed ahead of the module-relative sourceFile.
    assertEquals(
      "https://github.com/yschimke/compose-ai-tools/blob/main/" +
        "samples/design-catalog-compose-m3/src/main/kotlin/com/example/Home.kt",
      ServeUrls.githubBlobUrl(
        "yschimke/compose-ai-tools",
        "main",
        "samples/design-catalog-compose-m3",
        "src/main/kotlin/com/example/Home.kt",
      ),
    )
    // A `/`-bearing ref (tag/branch) is a valid blob path.
    assertEquals(
      "https://github.com/o/r/blob/release/1.0/mod/src/main/A.kt",
      ServeUrls.githubBlobUrl("o/r", "release/1.0", "mod", "src/main/A.kt"),
    )
    // No module ⇒ the source path is linked directly under the ref.
    assertEquals(
      "https://github.com/o/r/blob/main/src/main/A.kt",
      ServeUrls.githubBlobUrl("o/r", "main", null, "src/main/A.kt"),
    )
    assertEquals(
      "https://github.com/o/r/blob/main/src/main/A.kt",
      ServeUrls.githubBlobUrl("o/r", "main", "  ", "src/main/A.kt"),
    )
    // '/' separators survive; spaces and unsafe chars in a segment are percent-encoded.
    assertEquals(
      "https://github.com/o/r/blob/main/mod/src/My%20Screen.kt",
      ServeUrls.githubBlobUrl("o/r", "main", "mod", "src/My Screen.kt"),
    )
    // Backslashes normalize and leading/trailing slashes on module + path are trimmed.
    assertEquals(
      "https://github.com/o/r/blob/main/mod/src/main/A.kt",
      ServeUrls.githubBlobUrl("o/r", "main", "/mod/", "\\src\\main\\A.kt"),
    )
    // Catalogs conventionally publish Gradle project paths, not repository directory paths.
    assertEquals(
      "https://github.com/o/r/blob/main/previews/src/main/A.kt",
      ServeUrls.githubBlobUrl("o/r", "main", ":previews", "src/main/A.kt"),
    )
    assertEquals(
      "https://github.com/o/r/blob/main/samples/android/src/main/A.kt",
      ServeUrls.githubBlobUrl("o/r", "main", ":samples:android", "src/main/A.kt"),
    )
    // The root Gradle project contributes no directory prefix.
    assertEquals(
      "https://github.com/o/r/blob/main/src/main/A.kt",
      ServeUrls.githubBlobUrl("o/r", "main", ":", "src/main/A.kt"),
    )
  }

  @Test
  fun `github blob url is null when repo, ref, or source is missing`() {
    assertEquals(null, ServeUrls.githubBlobUrl(null, "main", "mod", "src/A.kt"))
    assertEquals(null, ServeUrls.githubBlobUrl("o/r", null, "mod", "src/A.kt"))
    assertEquals(null, ServeUrls.githubBlobUrl("o/r", "main", "mod", null))
    assertEquals(null, ServeUrls.githubBlobUrl("o/r", "main", "mod", "   "))
    assertEquals(null, ServeUrls.githubBlobUrl("", "main", "mod", "src/A.kt"))
  }

  @Test
  fun `history manifest url points at the delivery branch copy`() {
    assertEquals(
      "https://raw.githubusercontent.com/o/r/compose-preview/main/history.json",
      ServeUrls.historyManifestUrl("o/r", "compose-preview/main"),
    )
  }

  @Test
  fun `history manifest url is null without delivery provenance`() {
    // Null is the viewer's signal to leave the timeline out entirely, so an uploaded bundle or a
    // local project must not produce a URL.
    assertEquals(null, ServeUrls.historyManifestUrl(null, "main"))
    assertEquals(null, ServeUrls.historyManifestUrl("o/r", null))
    assertEquals(null, ServeUrls.historyManifestUrl("", "main"))
    assertEquals(null, ServeUrls.historyManifestUrl("not-a-repo", "main"))
  }

  @Test
  fun `historical render url addresses a commit, not the branch tip`() {
    // The branch tip only has the current bytes; the raw host serves any commit, which is what
    // makes an old version viewable.
    assertEquals(
      "https://raw.githubusercontent.com/o/r/df4aa9c00fcc8b1747e159b71d3fbc75cdc27b80/renders/m/A.png",
      ServeUrls.historicalRenderUrl(
        "o/r",
        "df4aa9c00fcc8b1747e159b71d3fbc75cdc27b80",
        "renders/m/A.png",
      ),
    )
  }

  @Test
  fun `historical render url encodes path segments`() {
    // Module ids carry colons and labels carry spaces; both must survive as one path segment.
    assertEquals(
      "https://raw.githubusercontent.com/o/r/abc1234/renders/samples%3Awear/Foo_Large%20Round.png",
      ServeUrls.historicalRenderUrl("o/r", "abc1234", "renders/samples:wear/Foo_Large Round.png"),
    )
  }

  @Test
  fun `historical render url rejects a ref where a sha is required`() {
    // The manifest records shas. Accepting a ref would let a malformed or hostile manifest steer
    // fetches at an attacker-chosen branch.
    assertEquals(null, ServeUrls.historicalRenderUrl("o/r", "main", "renders/m/A.png"))
    assertEquals(null, ServeUrls.historicalRenderUrl("o/r", "../../etc", "renders/m/A.png"))
    assertEquals(null, ServeUrls.historicalRenderUrl("o/r", "", "renders/m/A.png"))
  }

  @Test
  fun `historical render url rejects paths outside the renders tree`() {
    assertEquals(null, ServeUrls.historicalRenderUrl("o/r", "abc1234", "baselines.json"))
    assertEquals(null, ServeUrls.historicalRenderUrl("o/r", "abc1234", "renders/../secrets"))
    assertEquals(null, ServeUrls.historicalRenderUrl("o/r", "abc1234", null))
  }
}
