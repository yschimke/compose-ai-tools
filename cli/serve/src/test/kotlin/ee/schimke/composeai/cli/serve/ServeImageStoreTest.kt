package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The store's bounds: what it accepts, how long it keeps it, and what it drops when full. */
class ServeImageStoreTest {

  private var now = 1_700_000_000_000L

  private fun store(
    ttlSeconds: Long = 60,
    maxImages: Int = 64,
    maxBytes: Int = 1024 * 1024,
    maxTotalBytes: Long = 8L * 1024 * 1024,
  ) =
    ServeImageStore(
      ttlSeconds = ttlSeconds,
      maxImages = maxImages,
      maxBytes = maxBytes,
      maxTotalBytes = maxTotalBytes,
      clock = { now },
    )

  private fun ServeImageStore.put(
    name: String? = "before.png",
    bytes: ByteArray = ServeImageFixtures.png(),
    login: String = "octocat",
  ) = add(name, bytes, uploadedBy = login, isSecurityChecked = true)

  @Test
  fun `an accepted image is addressable at a path ending in its real extension`() {
    val store = store()
    val image = (store.put() as ServeImageStore.Result.Ok).image
    assertTrue(image.path.startsWith("/i/"), image.path)
    assertTrue(image.path.endsWith(".png"), image.path)
    assertEquals("before.png", image.name)
    assertEquals("octocat", image.uploadedBy)
    assertEquals(ServeDocSize(4, 3), image.dimensions)
    assertEquals(image.id, store.get(image.id)?.id)
  }

  @Test
  fun `the permalink's extension is part of the address`() {
    val store = store()
    val image = (store.put() as ServeImageStore.Result.Ok).image
    assertNotNull(store.get(image.id, ".png"))
    assertNotNull(store.get(image.id, ".PNG"), "the suffix is not case-sensitive")
    // A PNG must not come back under a name that says it is something else.
    assertNull(store.get(image.id, ".jpg"))
    assertNotNull(store.get(image.id, null), "a bare id still resolves")
  }

  @Test
  fun `a link stops resolving the moment its ttl is up`() {
    val store = store(ttlSeconds = 60)
    val image = (store.put() as ServeImageStore.Result.Ok).image
    now += 59_000
    assertNotNull(store.get(image.id))
    assertEquals(1, store.remainingSeconds(image))
    now += 2_000
    assertNull(store.get(image.id))
    assertEquals(0, store.occupancy().count)
  }

  @Test
  fun `an upload that is not an image, is empty, or is oversized is refused with a reason`() {
    val store = store(maxBytes = 64)
    val notAnImage = store.put(bytes = "<html>nope</html>".toByteArray())
    assertTrue(notAnImage is ServeImageStore.Result.Failed)
    assertTrue(
      notAnImage.reason.contains("unrecognised"),
      notAnImage.reason,
    )
    assertTrue(store.put(bytes = ByteArray(0)) is ServeImageStore.Result.Failed)
    assertTrue(
      store.put(bytes = ServeImageFixtures.png(width = 64, height = 64))
        is ServeImageStore.Result.Failed
    )
  }

  @Test
  fun `a burst evicts the oldest links rather than growing without bound`() {
    val store = store(maxImages = 3)
    val ids =
      (1..5).map {
        // Each upload lands a millisecond later, so "closest to expiry" is a total order.
        now += 1
        (store.put(name = "shot$it.png") as ServeImageStore.Result.Ok).image.id
      }
    assertEquals(3, store.occupancy().count)
    assertNull(store.get(ids[0]), "the first upload was evicted")
    assertNull(store.get(ids[1]))
    assertNotNull(store.get(ids[4]), "the newest upload survived")
  }

  @Test
  fun `the uploaded name is a label and never a path`() {
    val store = store()
    val image = (store.put(name = "../../etc/passwd") as ServeImageStore.Result.Ok).image
    assertEquals("passwd", image.name)
    // The format decides the extension, not the name — so a mislabelled upload can't pick its own.
    assertTrue(image.path.endsWith(".png"), image.path)
    val unnamed = (store.put(name = null) as ServeImageStore.Result.Ok).image
    assertEquals("PNG image", unnamed.name)
  }

  @Test
  fun `occupancy reports what the lane is holding`() {
    val store = store()
    store.put(login = "octocat")
    store.put(login = "hubot")
    store.put(login = "hubot")
    val occupancy = store.occupancy()
    assertEquals(3, occupancy.count)
    assertEquals(2, occupancy.uploaders)
    assertTrue(occupancy.totalBytes > 0)
  }
}
