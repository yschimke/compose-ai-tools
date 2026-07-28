package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The expiring-permalink store behind `POST /docs`: ingest, TTL, caps, and the SSRF gate. */
class ServeDocStoreTest {

  private var now = 1_000_000L
  private var ids = 0

  private fun store(
    ttlSeconds: Long = 60,
    maxDocs: Int = 8,
    maxBytes: Int = 1024,
    maxTotalBytes: Long = 4096,
    allowedHosts: List<String> = emptyList(),
    fetch: (String) -> ByteArray? = { null },
  ) =
    ServeDocStore(
      ttlSeconds = ttlSeconds,
      maxDocs = maxDocs,
      maxBytes = maxBytes,
      maxTotalBytes = maxTotalBytes,
      allowedHosts = allowedHosts,
      fetch = fetch,
      clock = { now },
      mintId = { "doc${(++ids).toString().padStart(16, '0')}" },
    )

  private fun ok(result: ServeDocStore.Result): ServeDocStore.Doc =
    (result as ServeDocStore.Result.Ok).doc

  private fun failure(result: ServeDocStore.Result): String =
    (result as ServeDocStore.Result.Failed).reason

  @Test
  fun `an accepted document gets a permalink that resolves until it expires`() {
    val store = store(ttlSeconds = 60)

    val doc = ok(store.add("loading.json", ServeDocFixtures.lottieDoc(), isSecurityChecked = true))
    assertEquals(ServeDocFormats.LOTTIE, doc.format)
    assertEquals("loading.json", doc.name)
    assertEquals("/d/${doc.id}", doc.path)
    assertEquals(60, doc.secondsUntilExpiry(now))
    assertEquals(doc.id, store.get(doc.id)?.id)

    now += 59_000
    assertEquals(doc.id, store.get(doc.id)?.id, "still live one second before expiry")

    now += 2_000
    assertNull(store.get(doc.id), "the link stops resolving once the TTL is up")
    assertTrue(store.snapshot().isEmpty(), "and the bytes are dropped, not merely hidden")
  }

  @Test
  fun `the format is sniffed from the bytes, never from the supplied name`() {
    val store = store()

    // A Remote Compose document uploaded under a .json name is still Remote Compose…
    val rc =
      ok(store.add("thing.json", ServeDocFixtures.remoteComposeDoc(), isSecurityChecked = true))
    assertEquals(ServeDocFormats.REMOTE_COMPOSE, rc.format)
    // …and a file named .rc that isn't one is refused rather than trusted.
    val reason = failure(store.add("evil.rc", "<html>nope</html>".toByteArray(), true))
    assertTrue(reason.startsWith("unrecognised document format"), reason)
  }

  @Test
  fun `oversized and empty uploads are refused`() {
    val store = store(maxBytes = 64)

    assertTrue(
      failure(store.add("big.json", ByteArray(65) { 'a'.code.toByte() }, true)).contains("exceeds")
    )
    assertEquals("empty document", failure(store.add("nothing", ByteArray(0), true)))
  }

  @Test
  fun `a name is only ever a label — path separators and control characters are stripped`() {
    val store = store()

    val doc = ok(store.add("../../etc/pa\u0000sswd.json", ServeDocFixtures.lottieDoc(), true))
    assertEquals("passwd.json", doc.name)
    // The permalink is minted, never derived from the name.
    assertTrue(doc.id.startsWith("doc"), doc.id)
    assertEquals("/d/${doc.id}", doc.path)
  }

  @Test
  fun `a nameless upload falls back to the format label`() {
    val store = store()
    assertEquals(
      "Lottie document",
      ok(store.add(null, ServeDocFixtures.lottieDoc(), isSecurityChecked = true)).name,
    )
  }

  @Test
  fun `the count and total-size caps evict the documents closest to expiry`() {
    val store = store(maxDocs = 2, maxTotalBytes = 100_000)

    val first = ok(store.add("a", ServeDocFixtures.lottieDoc("a"), true))
    now += 1000
    val second = ok(store.add("b", ServeDocFixtures.lottieDoc("b"), true))
    now += 1000
    val third = ok(store.add("c", ServeDocFixtures.lottieDoc("c"), true))

    assertNull(store.get(first.id), "the oldest share is evicted once the cap is reached")
    assertEquals(listOf(second.id, third.id), store.snapshot().map { it.id })
  }

  @Test
  fun `the url lane is fail-closed until a host is allowlisted`() {
    val body = ServeDocFixtures.lottieDoc()
    val closed = store(fetch = { body })
    assertTrue(
      failure(closed.addFromUrl(null, "https://example.com/a.json", true)).contains("allowlist")
    )
    assertTrue(closed.snapshot().isEmpty())

    val open = store(allowedHosts = listOf("example.com"), fetch = { body })
    assertEquals(
      ServeDocFormats.LOTTIE,
      ok(open.addFromUrl(null, "https://example.com/anim.json", true)).format,
    )
    // Only the allowlisted host — a redirect target or an internal address is still refused.
    assertTrue(
      failure(open.addFromUrl(null, "http://169.254.169.254/latest/meta-data", true))
        .contains("allowlist")
    )
    assertTrue(failure(open.addFromUrl(null, "file:///etc/passwd", true)).contains("allowlist"))
  }

  @Test
  fun `an injected fetcher owns the result, including a null one`() {
    // Same contract as the bundle store: a null from the override is a reported failure, not an
    // absent override — the store must not quietly fall through to the real network.
    var calls = 0
    val store =
      store(
        allowedHosts = listOf("example.com"),
        fetch = {
          calls++
          null
        },
      )

    assertEquals(
      "could not fetch https://example.com/a.json",
      failure(store.addFromUrl(null, "https://example.com/a.json", true)),
    )
    assertEquals(1, calls, "the override is the only transport consulted")
  }

  @Test
  fun `minted ids are unguessable and shaped like ids`() {
    val real = List(64) { ServeDocStore.randomId() }

    assertEquals(real.size, real.distinct().size, "ids never repeat")
    assertTrue(real.all { ServeDocStore.isWellFormedId(it) })
    assertNotEquals(real[0], real[1])
    // The shape check is what keeps a crafted id from reaching the map at all.
    assertTrue(!ServeDocStore.isWellFormedId("../../secret"))
    assertTrue(!ServeDocStore.isWellFormedId("short"))
  }
}
