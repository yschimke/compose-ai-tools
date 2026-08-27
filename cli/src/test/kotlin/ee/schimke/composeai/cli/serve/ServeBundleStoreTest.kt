package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleSigning
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServeBundleStoreTest {

  private fun tempRoot(): File =
    java.nio.file.Files.createTempDirectory("store").toFile().also { it.deleteOnExit() }

  private val registered = LinkedHashMap<String, ServeBundleHost>()

  private fun store(
    fetch: (String) -> ByteArray? = { null },
    allowedHosts: List<String> = emptyList(),
  ): ServeBundleStore =
    ServeBundleStore(
      tempRoot(),
      register = { n, h -> registered[n] = h },
      fetch = fetch,
      allowedHosts = allowedHosts,
    )

  private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray =
    ServeBundle.zip(linkedMapOf("index.html" to "<html></html>".toByteArray(), *entries))

  @Test
  fun `add unpacks previews and registers a servable session`() {
    val zip = zipOf("previews/com.example.Red.png" to byteArrayOf(1, 2, 3))
    val result = store().add("demo", zip, isSecurityChecked = true)

    assertEquals(ServeBundleStore.Result.Ok("demo", 1), result)
    val host = registered.getValue("demo")
    val ok = host.render("com.example.Red", PreviewOverrides()) as RenderOutcome.Ok
    assertTrue(byteArrayOf(1, 2, 3).contentEquals(ok.png))
  }

  @Test
  fun `error-only bundle registers a diagnostic preview`() {
    val error =
      """{"schema":"compose-preview-error/v1","exception":"java.lang.IllegalStateException","message":"boom","stackTrace":"trace"}"""
    val result =
      store()
        .add(
          "broken",
          zipOf("previews/com.example.Broken.error.json" to error.toByteArray()),
          isSecurityChecked = true,
        )

    assertEquals(ServeBundleStore.Result.Ok("broken", 1), result)
    val preview = registered.getValue("broken").previews.single()
    assertEquals("com.example.Broken", preview.id)
    assertEquals("java.lang.IllegalStateException", preview.renderFailure?.errorClass)
    assertEquals("boom", preview.renderFailure?.message)
    assertTrue(
      registered.getValue("broken").render(preview.id, PreviewOverrides()) is RenderOutcome.NotFound
    )
  }

  @Test
  fun `zip-slip entries are ignored, only previews are extracted`() {
    val zip =
      zipOf(
        "previews/com.example.Red.png" to byteArrayOf(1),
        "previews/../../evil.png" to byteArrayOf(9), // path traversal — must be skipped
        "secrets.txt" to byteArrayOf(7), // not under previews/ — ignored
      )
    val result = store().add("demo", zip, isSecurityChecked = true)

    assertEquals(ServeBundleStore.Result.Ok("demo", 1), result)
    assertEquals(listOf("com.example.Red"), registered.getValue("demo").previews.map { it.id })
  }

  @Test
  fun `override sidecars are extracted and surfaced as a preview's declared knobs`() {
    val overrides =
      """{"declarations":[{"key":"label","type":"string",""" +
        """"default":{"kind":"string","value":"Tap me"},""" +
        """"current":{"kind":"string","value":"Tap me"}},""" +
        """{"key":"rowLabel","type":"string","index":0,""" +
        """"default":{"kind":"string","value":"Item 1"}}]}"""
    val zip =
      zipOf(
        "previews/com.example.Red.png" to byteArrayOf(1, 2, 3),
        "previews/com.example.Red.overrides.json" to overrides.toByteArray(),
      )
    val result = store().add("demo", zip, isSecurityChecked = true)
    assertEquals(ServeBundleStore.Result.Ok("demo", 1), result)

    val preview = registered.getValue("demo").previews.single { it.id == "com.example.Red" }
    assertEquals(listOf("label", "rowLabel"), preview.overrides.map { it.key })
    assertEquals(0, preview.overrides[1].index)
  }

  @Test
  fun `remotecompose sidecars are extracted and surfaced as a preview's RC knobs`() {
    // The upload path (POST / URL) must keep `.remotecompose.json` sidecars just like the
    // `.overrides.json` ones — otherwise an uploaded bundle would silently drop
    // `remoteComposeKnobs`
    // that the live-bundle / directory paths carry.
    val rc =
      """{"declarations":[{"name":"shaderColor",""" +
        """"default":{"kind":"color","argb":"#FF7DE2FF"}}]}"""
    val zip =
      zipOf(
        "previews/com.example.Red.png" to byteArrayOf(1, 2, 3),
        "previews/com.example.Red.remotecompose.json" to rc.toByteArray(),
      )
    val result = store().add("demo", zip, isSecurityChecked = true)
    assertEquals(ServeBundleStore.Result.Ok("demo", 1), result)

    val preview = registered.getValue("demo").previews.single { it.id == "com.example.Red" }
    assertEquals(listOf("shaderColor"), preview.remoteComposeKnobs.map { it.name })
  }

  @Test
  fun `ir rc sidecars are extracted and served as the browser player's document`() {
    // The upload path must keep the captured Remote Compose docs from the sibling `ir/` tree so the
    // client-side player lane (`GET /render/<id>.rc`) works on an uploaded bundle, not just the
    // directory path that reads the bundle dir straight from disk.
    val doc = byteArrayOf(0x52, 0x43, 0x01, 0x02, 0x03) // arbitrary RC doc bytes
    val zip =
      zipOf(
        "previews/com.example.Red.png" to byteArrayOf(1, 2, 3),
        "ir/com.example.Red.rc" to doc,
        "ir/../../evil.rc" to byteArrayOf(9), // path traversal — must be skipped
      )
    val result = store().add("demo", zip, isSecurityChecked = true)
    assertEquals(ServeBundleStore.Result.Ok("demo", 1), result)

    val host = registered.getValue("demo")
    assertTrue(doc.contentEquals(host.remoteComposeDoc("com.example.Red")))
    // A preview with no `ir/<id>.rc` sidecar has no client-side document.
    assertEquals(null, host.remoteComposeDoc("com.example.Missing"))
    // The cheap existence check (which gates the viewer's canvas lane) agrees with the byte read.
    assertTrue(host.hasRemoteComposeDoc("com.example.Red"))
    assertEquals(false, host.hasRemoteComposeDoc("com.example.Missing"))
  }

  @Test
  fun `the root previews_json is extracted so an uploaded bundle surfaces its declared themes`() {
    val previewsJson =
      """
      {"module":":samples:cmp","variant":"debug","previews":[
        {"id":"com.example.Card","functionName":"Card","className":"com.example.CardKt"},
        {"id":"themecatalog__Brand_Dark","functionName":"Brand Dark theme",
         "className":"com.example.BrandDarkTheme",
         "params":{"name":"Brand Dark","group":"Brand","kind":"THEME_CATALOG",
                   "wrapperClassName":"com.example.BrandDarkTheme"}}]}
      """
        .trimIndent()
    val zip =
      zipOf(
        "previews/com.example.Card.png" to byteArrayOf(1, 2, 3),
        "previews.json" to previewsJson.toByteArray(),
      )
    assertEquals(
      ServeBundleStore.Result.Ok("demo", 1),
      store().add("demo", zip, isSecurityChecked = true),
    )
    // The uploaded bundle's App theme selector is fed from the root previews.json — which must be
    // extracted alongside the PNGs (it isn't a `previews/…` entry).
    assertEquals(
      listOf("Brand Dark" to "com.example.BrandDarkTheme"),
      registered.getValue("demo").declaredThemes.map { it.name to it.providerFqn },
    )
  }

  @Test
  fun `a bundle without previews is rejected`() {
    val result = store().add("demo", zipOf("notes.txt" to byteArrayOf(1)), isSecurityChecked = true)
    assertTrue(result is ServeBundleStore.Result.Failed)
    assertTrue(registered.isEmpty())
  }

  @Test
  fun `unsafe session names are rejected`() {
    val zip = zipOf("previews/p.png" to byteArrayOf(1))
    assertTrue(
      store().add("../etc", zip, isSecurityChecked = true) is ServeBundleStore.Result.Failed
    )
    assertTrue(store().add("a/b", zip, isSecurityChecked = true) is ServeBundleStore.Result.Failed)
    // Dot-only names match the char class but would delete the upload root / its parent.
    assertTrue(store().add(".", zip, isSecurityChecked = true) is ServeBundleStore.Result.Failed)
    assertTrue(store().add("..", zip, isSecurityChecked = true) is ServeBundleStore.Result.Failed)
    assertTrue(store().add("...", zip, isSecurityChecked = true) is ServeBundleStore.Result.Failed)
  }

  @Test
  fun `a bundle larger than the cap is rejected`() {
    val store =
      ServeBundleStore(tempRoot(), register = { n, h -> registered[n] = h }, maxBytes = 1_000)
    val zip = zipOf("previews/p.png" to ByteArray(4_000))
    assertTrue(store.add("big", zip, isSecurityChecked = true) is ServeBundleStore.Result.Failed)
    assertTrue(registered.isEmpty())
  }

  @Test
  fun `addFromUrl fetches an allowed host then registers`() {
    val zip = zipOf("previews/p.png" to byteArrayOf(5))
    val result =
      store(
          fetch = { url -> if (url == "https://ci.example.com/art.zip") zip else null },
          allowedHosts = listOf("ci.example.com"),
        )
        .addFromUrl("fromci", "https://ci.example.com/art.zip", isSecurityChecked = true)
    assertEquals(ServeBundleStore.Result.Ok("fromci", 1), result)
    assertTrue(registered.containsKey("fromci"))
  }

  @Test
  fun `addFromUrl reports a fetch failure`() {
    val result =
      store(fetch = { null }, allowedHosts = listOf("ci.example.com"))
        .addFromUrl("x", "https://ci.example.com/nope", isSecurityChecked = true)
    assertTrue(result is ServeBundleStore.Result.Failed)
  }

  @Test
  fun `addFromUrl refuses a host not on the allowlist (SSRF) without fetching`() {
    var fetched = false
    val result =
      store(
          fetch = {
            fetched = true
            null
          },
          allowedHosts = listOf("ci.example.com"),
        )
        .addFromUrl("evil", "http://169.254.169.254/latest/meta-data", isSecurityChecked = true)
    assertTrue(result is ServeBundleStore.Result.Failed)
    assertTrue(!fetched, "a disallowed host must not be fetched")
  }

  @Test
  fun `addFromUrl fails closed when no hosts are allowed`() {
    var fetched = false
    val result =
      store(
          fetch = {
            fetched = true
            null
          },
          allowedHosts = emptyList(),
        )
        .addFromUrl("x", "https://ci.example.com/art.zip", isSecurityChecked = true)
    assertTrue(result is ServeBundleStore.Result.Failed)
    assertTrue(!fetched, "an empty allowlist fetches nothing")
  }

  @Test
  fun `an injected fetcher owns the result, including a null one`() {
    // A null from the override means "this fetch failed", not "there is no override" — otherwise
    // the store falls through to the real network behind a test's back (a live request to the
    // allowlisted host, and its DNS/connect timeout).
    var calls = 0
    val result =
      store(
          fetch = {
            calls++
            null
          },
          allowedHosts = listOf("ci.example.com"),
        )
        .addFromUrl("x", "https://ci.example.com/nope.zip", isSecurityChecked = true)

    assertTrue(result is ServeBundleStore.Result.Failed)
    assertEquals(1, calls, "the override is the only transport consulted")
  }

  @Test
  fun `addFromUrl refuses a look-alike or subdomain of an allowed host`() {
    // The allowlist is exact-host (shared with the document lane via ServeUrlFetch), so neither a
    // suffix trick nor a subdomain of a trusted CI host gets fetched.
    for (url in
      listOf("https://ci.example.com.evil.test/art.zip", "https://sub.ci.example.com/art.zip")) {
      var fetched = false
      val result =
        store(
            fetch = {
              fetched = true
              null
            },
            allowedHosts = listOf("ci.example.com"),
          )
          .addFromUrl("x", url, isSecurityChecked = true)
      assertTrue(result is ServeBundleStore.Result.Failed, url)
      assertTrue(!fetched, "$url must not be fetched")
    }
  }

  @Test
  fun `addFromUrl refuses a non-http scheme`() {
    val result =
      store(fetch = { ByteArray(0) }, allowedHosts = listOf("ci.example.com"))
        .addFromUrl("x", "file:///etc/passwd", isSecurityChecked = true)
    assertTrue(result is ServeBundleStore.Result.Failed)
  }

  // --- producer-trust verification on ingestion -------------------------------------------------

  /** A minimal valid PNG to front the signed polyglot the store must accept and strip. */
  private fun pngCover(): ByteArray {
    val baos = ByteArrayOutputStream()
    ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", baos)
    return baos.toByteArray()
  }

  /** Build a signed PNG+ZIP polyglot upload (cover + previews + a real Ed25519 signature). */
  private fun signedPolyglot(name: String): Pair<ByteArray, BundleSigning.KeyPairB64> {
    val keys = BundleSigning.generateKeyPair()
    val zip = zipOf("previews/com.example.Red.png" to byteArrayOf(1, 2, 3))
    val file =
      File(tempRoot(), name).also {
        it.outputStream().use { o ->
          o.write(pngCover())
          o.write(zip)
        }
      }
    val digest = BundleSigning.canonicalDigest(file)
    BundleSigning.addSignature(
      file,
      BundleSigning.Signature(
        keyId = "ci",
        digest = BundleSigning.hex(digest),
        signature =
          BundleSigning.base64(
            BundleSigning.signEd25519(BundleSigning.parsePrivateKey(keys.privateKeyB64), digest)
          ),
      ),
    )
    return file.readBytes() to keys
  }

  @Test
  fun `a signed bundle from a trusted key is attributed by signature`() {
    val (bytes, keys) = signedPolyglot("signed.png")
    val store =
      ServeBundleStore(
        tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { TrustStore(keys = listOf(TrustedKey("ci", keys.publicKeyB64))) },
      )
    val result = store.add("signed", bytes, isSecurityChecked = true)
    assertEquals(ServeBundleStore.Result.Ok("signed", 1, "signature:ci"), result)
    assertTrue(registered.getValue("signed").trust is BundleVerifier.Verdict.Trusted)
  }

  @Test
  fun `a signed bundle is unverified when its key is not trusted`() {
    val (bytes, _) = signedPolyglot("untrusted.png")
    // The default store has the empty (fail-closed) trust store — the signature is present but the
    // key isn't pinned, so the bundle still serves its data tiers as unverified.
    val result = store().add("u", bytes, isSecurityChecked = true)
    assertEquals(ServeBundleStore.Result.Ok("u", 1, "unverified"), result)
    assertTrue(registered.getValue("u").trust is BundleVerifier.Verdict.Unverified)
  }
}
