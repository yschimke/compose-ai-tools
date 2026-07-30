package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * The expiring preview-token store behind `/pg/<token>`: mint, redeem, TTL, caps, and work-dir
 * cleanup.
 */
class PlaygroundTokenStoreTest {

  private var now = 1_000_000L
  private var ids = 0
  private val fs = FakeFileSystem()

  private fun store(ttlSeconds: Long = 600, maxTokens: Int = 8) =
    PlaygroundTokenStore(
      ttlSeconds = ttlSeconds,
      maxTokens = maxTokens,
      fileSystem = fs,
      clock = { now },
      mintId = { "pg_${(++ids).toString().padStart(16, '0')}" },
    )

  /** A snippet whose work dir actually exists on the fake FS, so cleanup is observable. */
  private fun snippet(
    name: String,
    mode: PlaygroundMode = PlaygroundMode.CMP,
  ): PlaygroundTokenStore.PlaygroundSnippet {
    val work = "/work/$name".toPath()
    val classes = work / "classes"
    fs.createDirectories(classes)
    fs.write(classes / "Snippet.class") { writeUtf8("cafebabe") }
    return PlaygroundTokenStore.PlaygroundSnippet(
      mode = mode,
      workDir = work,
      classesDir = classes,
      classpath = listOf(classes),
      moduleName = "playground-$name",
      previewId = "com.example.$name.Preview",
    )
  }

  @Test
  fun `a minted token redeems until it expires, then is gone`() {
    val store = store(ttlSeconds = 600)

    val token = store.add(snippet("a"), isSecurityChecked = true)
    assertEquals("/pg/${token.id}", token.path)
    assertTrue(PlaygroundTokenStore.isWellFormedId(token.id), token.id)
    assertEquals(600, token.secondsUntilExpiry(now))
    assertEquals(token.id, store.get(token.id)?.id)

    now += 599_000
    assertEquals(token.id, store.get(token.id)?.id, "still redeemable one second before expiry")

    now += 2_000
    assertNull(store.get(token.id), "the link stops resolving once the TTL is up")
    assertTrue(store.snapshot().isEmpty())
    assertFalse(fs.exists("/work/a".toPath()), "and the work dir is deleted, not merely hidden")
  }

  @Test
  fun `the snippet's mode and preview id survive the round trip`() {
    val store = store()
    val token = store.add(snippet("wear", mode = PlaygroundMode.ANDROID), isSecurityChecked = true)
    val got = store.get(token.id)!!.snippet
    assertEquals(PlaygroundMode.ANDROID, got.mode)
    assertEquals("com.example.wear.Preview", got.previewId)
  }

  @Test
  fun `overflow evicts the nearest-expiry token and deletes its work dir`() {
    val store = store(maxTokens = 2)

    val first = store.add(snippet("first"), isSecurityChecked = true)
    now += 1_000
    store.add(snippet("second"), isSecurityChecked = true)
    now += 1_000
    store.add(snippet("third"), isSecurityChecked = true) // pushes over the cap of 2

    assertNull(store.get(first.id), "the oldest token is evicted on overflow")
    assertFalse(fs.exists("/work/first".toPath()), "and its work dir is cleaned up")
    assertEquals(2, store.snapshot().size)
  }

  @Test
  fun `explicit remove drops the token and its work dir`() {
    val store = store()
    val token = store.add(snippet("gone"), isSecurityChecked = true)

    assertTrue(store.remove(token.id))
    assertNull(store.get(token.id))
    assertFalse(fs.exists("/work/gone".toPath()))
    assertFalse(store.remove(token.id), "removing an unknown id is a no-op")
  }

  @Test
  fun `clear drops every token and work dir`() {
    val store = store()
    store.add(snippet("x"), isSecurityChecked = true)
    store.add(snippet("y"), isSecurityChecked = true)

    store.clear()
    assertTrue(store.snapshot().isEmpty())
    assertFalse(fs.exists("/work/x".toPath()))
    assertFalse(fs.exists("/work/y".toPath()))
  }

  @Test
  fun `confType maps to the right mode`() {
    assertEquals(PlaygroundMode.CMP, PlaygroundMode.fromConfType("compose-cmp"))
    assertEquals(PlaygroundMode.ANDROID, PlaygroundMode.fromConfType("compose-android"))
    assertEquals(PlaygroundMode.REMOTE_COMPOSE, PlaygroundMode.fromConfType("remote-compose"))
    assertEquals(PlaygroundMode.CMP, PlaygroundMode.fromConfType("canvas"), "stock frontend target")
    assertEquals(PlaygroundMode.CMP, PlaygroundMode.fromConfType(null), "absent confType")
  }
}
