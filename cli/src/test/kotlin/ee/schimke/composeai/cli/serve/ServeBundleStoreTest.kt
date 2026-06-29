package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.io.File
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
  fun `addFromUrl refuses a non-http scheme`() {
    val result =
      store(fetch = { ByteArray(0) }, allowedHosts = listOf("ci.example.com"))
        .addFromUrl("x", "file:///etc/passwd", isSecurityChecked = true)
    assertTrue(result is ServeBundleStore.Result.Failed)
  }
}
