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

  private fun store(fetch: (String) -> ByteArray? = { null }): ServeBundleStore =
    ServeBundleStore(tempRoot(), register = { n, h -> registered[n] = h }, fetch = fetch)

  private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray =
    ServeBundle.zip(linkedMapOf("index.html" to "<html></html>".toByteArray(), *entries))

  @Test
  fun `add unpacks previews and registers a servable session`() {
    val zip = zipOf("previews/com.example.Red.png" to byteArrayOf(1, 2, 3))
    val result = store().add("demo", zip)

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
    val result = store().add("demo", zip)

    assertEquals(ServeBundleStore.Result.Ok("demo", 1), result)
    assertEquals(listOf("com.example.Red"), registered.getValue("demo").previews.map { it.id })
  }

  @Test
  fun `a bundle without previews is rejected`() {
    val result = store().add("demo", zipOf("notes.txt" to byteArrayOf(1)))
    assertTrue(result is ServeBundleStore.Result.Failed)
    assertTrue(registered.isEmpty())
  }

  @Test
  fun `unsafe session names are rejected`() {
    val zip = zipOf("previews/p.png" to byteArrayOf(1))
    assertTrue(store().add("../etc", zip) is ServeBundleStore.Result.Failed)
    assertTrue(store().add("a/b", zip) is ServeBundleStore.Result.Failed)
    // Dot-only names match the char class but would delete the upload root / its parent.
    assertTrue(store().add(".", zip) is ServeBundleStore.Result.Failed)
    assertTrue(store().add("..", zip) is ServeBundleStore.Result.Failed)
    assertTrue(store().add("...", zip) is ServeBundleStore.Result.Failed)
  }

  @Test
  fun `a bundle larger than the cap is rejected`() {
    val store =
      ServeBundleStore(tempRoot(), register = { n, h -> registered[n] = h }, maxBytes = 1_000)
    val zip = zipOf("previews/p.png" to ByteArray(4_000))
    assertTrue(store.add("big", zip) is ServeBundleStore.Result.Failed)
    assertTrue(registered.isEmpty())
  }

  @Test
  fun `addFromUrl fetches then registers`() {
    val zip = zipOf("previews/p.png" to byteArrayOf(5))
    val result =
      store(fetch = { url -> if (url == "https://ci/art.zip") zip else null })
        .addFromUrl("fromci", "https://ci/art.zip")
    assertEquals(ServeBundleStore.Result.Ok("fromci", 1), result)
    assertTrue(registered.containsKey("fromci"))
  }

  @Test
  fun `addFromUrl reports a fetch failure`() {
    val result = store(fetch = { null }).addFromUrl("x", "https://nope")
    assertTrue(result is ServeBundleStore.Result.Failed)
  }
}
