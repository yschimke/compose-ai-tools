package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * The Stage-2 redemption orchestration: token lookup → materialize → register (once) → live, and
 * release-on-expiry — driven against a real [ServeSessionRegistry] (its `open` callback is the
 * observation point) and a fake `materialize`, so no real daemon is stood up.
 */
class PlaygroundRedeemServiceTest {

  private val fs = FakeFileSystem()
  private var now = 1_000L

  // The registry's resume callback records the state it's asked to open — a live entry that this
  // test never leases invokes `open` on `acquire`, so it doubles as a "was this id registered?"
  // probe.
  private val opened = mutableListOf<ServeSessionState>()
  private val registry =
    ServeSessionRegistry(
      open = {
        opened.add(it)
        null
      },
      clock = { now },
    )

  private lateinit var service: PlaygroundRedeemService
  private var materializeCount = 0

  private val store =
    PlaygroundTokenStore(fileSystem = fs, clock = { now }, onRemove = { service.release(it.id) })

  init {
    service =
      PlaygroundRedeemService(
        tokenStore = store,
        registry = registry,
        materialize = {
          materializeCount++
          ServeSessionState(
            descriptor = File("/w/daemon-launch.json"),
            workspaceRoot = File("/w"),
            workspaceName = "w",
            previews = emptyList(),
            label = "playground",
          )
        },
      )
  }

  @AfterTest fun close() = runCatching { registry.close() }.let {}

  private fun mint(previewId: String = "com.example.SnippetKt.Preview") =
    store.add(
      PlaygroundTokenStore.PlaygroundSnippet(
        mode = PlaygroundMode.CMP,
        workDir = "/w/snippet".toPath(),
        classesDir = "/w/snippet/classes".toPath(),
        classpath = listOf("/w/snippet/classes".toPath()),
        moduleName = "playground-cmp",
        previewId = previewId,
      ),
      isSecurityChecked = true,
    )

  @Test
  fun `an unknown token is not found`() {
    assertEquals(PlaygroundRedeemService.Outcome.NotFound, service.redeem("pg_missing"))
    assertEquals(0, materializeCount)
  }

  @Test
  fun `a live token redeems to a registered session and re-redeem reuses it`() {
    val token = mint()
    val first = service.redeem(token.id)
    assertEquals(
      PlaygroundRedeemService.Outcome.Live(token.id, "com.example.SnippetKt.Preview"),
      first,
    )
    // Registered: acquiring the id resumes it through the registry's `open` callback.
    registry.acquire(token.id)
    assertEquals(1, opened.size, "the redeemed snippet's state was registered under the token id")

    // A second redeem within the TTL rides the standing session — no re-materialize.
    assertEquals(first, service.redeem(token.id))
    assertEquals(1, materializeCount, "re-redeem reuses the session rather than re-materializing")
  }

  @Test
  fun `a host with no live backend reports unavailable`() {
    service =
      PlaygroundRedeemService(tokenStore = store, registry = registry, materialize = { null })
    val token = mint()
    assertEquals(PlaygroundRedeemService.Outcome.Unavailable, service.redeem(token.id))
    registry.acquire(token.id)
    assertTrue(opened.isEmpty(), "an unavailable redemption registers nothing")
  }

  @Test
  fun `an expiring token releases its live session`() {
    val token = mint()
    service.redeem(token.id)
    registry.acquire(token.id)
    assertEquals(1, opened.size)
    opened.clear()

    // Advance past the TTL and purge: the store's removal hook must unregister the session.
    now += store.ttlSeconds * 1000 + 1
    assertEquals(1, store.purgeExpired(), "the expired token is dropped")
    registry.acquire(token.id)
    assertTrue(opened.isEmpty(), "the released session is no longer registered (open not invoked)")
  }
}
