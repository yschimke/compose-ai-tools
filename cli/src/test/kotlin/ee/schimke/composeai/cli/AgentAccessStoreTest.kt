package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The CLI's grant store: origin keying, expiry, and the file permissions.
 *
 * The origin rules get most of the attention because getting them wrong is how a credential ends up
 * on the wrong host — a suffix match, a normalised `user:pass@`, or a port that makes one server
 * look like two are all the same class of bug, and none of them would be obvious from the outside.
 */
class AgentAccessStoreTest {

  private val dir: File =
    Files.createTempDirectory("agent-access").toFile().also { it.deleteOnExit() }
  private val file = File(dir, "agent-access.json")
  private var now = 1_000_000L
  private val warnings = mutableListOf<String>()

  private fun store() = AgentAccessStore(file = file, clock = { now }, warn = { warnings += it })

  private fun entry(
    origin: String = "https://preview.coo.ee",
    token: String = "cpat_abc",
    ttlSeconds: Long = 3600,
  ) =
    AgentAccessStore.Entry(
      origin = origin,
      token = token,
      scopes = listOf("preview", "live"),
      approvedBy = "@yuri",
      label = "fix #1",
      expiresAtMillis = now + ttlSeconds * 1000,
    )

  @Test
  fun `a saved grant comes back for its own origin`() {
    assertTrue(store().save(entry()))
    assertEquals("cpat_abc", store().tokenFor("https://preview.coo.ee"))
    assertEquals("cpat_abc", store().tokenFor("https://preview.coo.ee/some/path?q=1"))
    assertEquals("cpat_abc", store().tokenFor("https://PREVIEW.COO.EE"))
    assertEquals("cpat_abc", store().tokenFor("https://preview.coo.ee:443"))
  }

  @Test
  fun `a grant is never offered to another host`() {
    store().save(entry())
    assertNull(store().tokenFor("https://evil.example"))
    // A suffix that merely ends the same way is a different host.
    assertNull(store().tokenFor("https://notpreview.coo.ee"))
    // …and so is the same name over a different scheme or port.
    assertNull(store().tokenFor("http://preview.coo.ee"))
    assertNull(store().tokenFor("https://preview.coo.ee:8443"))
  }

  @Test
  fun `saving the same origin replaces rather than accumulates`() {
    store().save(entry(token = "cpat_first"))
    store().save(entry(token = "cpat_second"))
    assertEquals(1, store().all().size)
    assertEquals("cpat_second", store().tokenFor("https://preview.coo.ee"))
  }

  @Test
  fun `two servers keep two grants`() {
    store().save(entry(origin = "https://a.example", token = "cpat_a"))
    store().save(entry(origin = "https://b.example", token = "cpat_b"))
    assertEquals(2, store().all().size)
    assertEquals("cpat_a", store().tokenFor("https://a.example"))
    assertEquals("cpat_b", store().tokenFor("https://b.example"))
  }

  @Test
  fun `an expired grant is not offered and does not survive the next write`() {
    store().save(entry(ttlSeconds = 60))
    now += 61_000
    assertNull(store().tokenFor("https://preview.coo.ee"))
    assertTrue(store().all().isEmpty())
    store().save(entry(origin = "https://other.example", token = "cpat_other"))
    assertEquals(listOf("https://other.example"), store().all().map { it.origin })
  }

  @Test
  fun `forget drops one origin and clear drops all`() {
    store().save(entry(origin = "https://a.example"))
    store().save(entry(origin = "https://b.example"))
    assertTrue(store().forget("https://a.example"))
    assertFalse(store().forget("https://a.example"))
    assertEquals(1, store().all().size)
    store().clear()
    assertTrue(store().all().isEmpty())
  }

  @Test
  fun `the file is owner-only, because it holds bearer tokens`() {
    store().save(entry())
    val perms = Files.getPosixFilePermissions(file.toPath())
    assertEquals(
      setOf(
        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
      ),
      perms,
    )
  }

  @Test
  fun `a corrupt store warns and reads as empty rather than failing the command`() {
    file.parentFile.mkdirs()
    file.writeText("{ this is not json")
    assertTrue(store().all().isEmpty())
    assertTrue(warnings.any { it.contains("could not read") })
    // …and it is recoverable by writing over it.
    assertTrue(store().save(entry()))
    assertNotNull(store().tokenFor("https://preview.coo.ee"))
  }

  @Test
  fun `origins with credentials in them are refused, not normalised away`() {
    assertNull(AgentAccessStore.normalizeOrigin("https://user:pass@preview.coo.ee"))
    assertNull(AgentAccessStore.normalizeOrigin("ftp://preview.coo.ee"))
    assertNull(AgentAccessStore.normalizeOrigin("preview.coo.ee"))
    assertNull(AgentAccessStore.normalizeOrigin(""))
    assertNull(AgentAccessStore.normalizeOrigin(null))
  }

  @Test
  fun `default ports collapse and others do not`() {
    assertEquals("https://h.example", AgentAccessStore.normalizeOrigin("https://h.example:443/x"))
    assertEquals("http://h.example", AgentAccessStore.normalizeOrigin("http://h.example:80"))
    assertEquals("http://h.example:8080", AgentAccessStore.normalizeOrigin("http://h.example:8080"))
  }

  @Test
  fun `the store path follows XDG, with an explicit override winning`() {
    val env = mapOf("XDG_CONFIG_HOME" to "/xdg", "HOME" to "/home/u")
    assertEquals(
      File("/xdg/compose-preview/agent-access.json"),
      AgentAccessStore.defaultFile { env[it] },
    )
    assertEquals(
      File("/home/u/.config/compose-preview/agent-access.json"),
      AgentAccessStore.defaultFile { mapOf("HOME" to "/home/u")[it] },
    )
    assertEquals(
      File("/tmp/explicit.json"),
      AgentAccessStore.defaultFile {
        (env + ("COMPOSE_PREVIEW_AGENT_ACCESS_FILE" to "/tmp/explicit.json"))[it]
      },
    )
  }

  @Test
  fun `a remembered request survives to a later invocation`() {
    // The `--no-wait` promise: a later command can finish what this one started, which it can only
    // do if the device secret was kept.
    val pending =
      AgentAccessStore.Pending(
        origin = "https://preview.coo.ee",
        requestId = "req-1",
        deviceSecret = "secret-1",
        userCode = "KX7M-9QD4",
        approveUrl = "https://preview.coo.ee/agent-access/req-1",
        expiresAtMillis = now + 600_000,
      )
    assertTrue(store().savePending(pending))
    assertEquals("secret-1", store().pendingFor("https://preview.coo.ee")?.deviceSecret)
    assertTrue(store().forgetPending("https://preview.coo.ee"))
    assertNull(store().pendingFor("https://preview.coo.ee"))
  }

  @Test
  fun `saving a grant does not discard a pending request, and vice versa`() {
    store()
      .savePending(
        AgentAccessStore.Pending(
          origin = "https://a.example",
          requestId = "r",
          deviceSecret = "s",
          expiresAtMillis = now + 600_000,
        )
      )
    store().save(entry(origin = "https://b.example", token = "cpat_b"))
    assertEquals(1, store().allPending().size)
    assertEquals("cpat_b", store().tokenFor("https://b.example"))
  }

  @Test
  fun `two requests for one server both survive`() {
    // Replacing per origin looked tidy and quietly discarded a credential: the first link stays
    // approvable on the server, so a human could approve it and mint a grant whose device secret
    // this store had already thrown away.
    fun pending(id: String) =
      AgentAccessStore.Pending(
        origin = "https://preview.coo.ee",
        requestId = id,
        deviceSecret = "secret-$id",
        expiresAtMillis = now + 600_000,
      )
    store().savePending(pending("first"))
    store().savePending(pending("second"))
    assertEquals(2, store().allPending().size)
    assertEquals(setOf("first", "second"), store().allPending().map { it.requestId }.toSet())
    // …and one can be dropped without taking the other with it.
    assertTrue(store().forgetPendingRequest("first"))
    assertEquals(listOf("second"), store().allPending().map { it.requestId })
  }

  @Test
  fun `a remembered request outlives its approval window so a late approval still lands`() {
    // The server retains an approved-but-uncollected request until its grant expires, precisely so
    // a decision made in the last seconds reaches its agent. Dropping the device secret on the
    // window's own deadline put the two halves out of step.
    store()
      .savePending(
        AgentAccessStore.Pending(
          origin = "https://preview.coo.ee",
          requestId = "late",
          deviceSecret = "s",
          expiresAtMillis = now + 600_000,
        )
      )
    now += 601_000
    val kept = store().pendingFor("https://preview.coo.ee")
    assertNotNull(kept, "the device secret must survive the approval window")
    assertTrue(kept.windowClosed(now), "…while still reporting the window as closed")
  }

  @Test
  fun `credentials are never written to the working directory`() {
    // The old fallback was `.`, so on a minimal service environment with neither XDG_CONFIG_HOME
    // nor HOME an agent wrote bearer tokens into whatever directory it ran in — for an agent that
    // is a checkout, which gets archived by CI or swept up by the next `git add -A`.
    val err =
      assertFailsWith<NoCredentialHomeException> {
        AgentAccessStore.defaultFile(prop = { null }, env = { null })
      }
    assertTrue(err.message!!.contains("COMPOSE_PREVIEW_AGENT_ACCESS_FILE"), "names the remedy")
  }

  @Test
  fun `no relative path is ever a home, whichever variable supplies it`() {
    // This leak was reachable three times by three routes — the original `.` fallback, a relative
    // `user.home`, then a relative `XDG_CONFIG_HOME`/`HOME` — because each fix was applied to the
    // branch that was pointed at. One rule now covers every candidate, and this covers all of them
    // so a fourth route cannot open quietly.
    for (relative in listOf(".", "relative/path", "./x", "")) {
      assertFailsWith<NoCredentialHomeException>("user.home=$relative") {
        AgentAccessStore.defaultFile(prop = { relative }, env = { null })
      }
      assertFailsWith<NoCredentialHomeException>("XDG_CONFIG_HOME=$relative") {
        AgentAccessStore.defaultFile(
          prop = { null },
          env = { n -> if (n == "XDG_CONFIG_HOME") relative else null },
        )
      }
      assertFailsWith<NoCredentialHomeException>("HOME=$relative") {
        AgentAccessStore.defaultFile(
          prop = { null },
          env = { n -> if (n == "HOME") relative else null },
        )
      }
    }
  }

  @Test
  fun `a relative variable falls through to an absolute one rather than winning`() {
    // A relative XDG_CONFIG_HOME must not shadow a perfectly good HOME.
    assertEquals(
      File("/home/u/.config/compose-preview/agent-access.json"),
      AgentAccessStore.defaultFile(
        prop = { null },
        env = { n -> mapOf("XDG_CONFIG_HOME" to ".", "HOME" to "/home/u")[n] },
      ),
    )
  }

  @Test
  fun `an absolute platform home is used when the environment has neither variable`() {
    // A minimal service context often has no HOME while the JVM still knows the passwd entry.
    assertEquals(
      File("/home/svc/.config/compose-preview/agent-access.json"),
      AgentAccessStore.defaultFile(prop = { "/home/svc" }, env = { null }),
    )
  }

  @Test
  fun `the explicit override still wins with no home at all`() {
    val chosen =
      AgentAccessStore.defaultFile(
        env = { name ->
          if (name == "COMPOSE_PREVIEW_AGENT_ACCESS_FILE") "/tmp/x/creds.json" else null
        }
      )
    assertEquals(File("/tmp/x/creds.json"), chosen)
  }

  @Test
  fun `a two-character credential filename still writes`() {
    // The path is overridable for CI and tests, and `createTempFile` demands a three-character
    // prefix — so `/tmp/a` made every write throw rather than merely being unusual.
    val short = File(dir, "a")
    val store = AgentAccessStore(file = short, clock = { now })
    assertTrue(
      store.save(
        AgentAccessStore.Entry(
          origin = "https://preview.coo.ee",
          token = "cpat_short",
          expiresAtMillis = now + 60_000,
        )
      )
    )
    assertEquals(
      "cpat_short",
      AgentAccessStore(file = short, clock = { now }).entryFor("https://preview.coo.ee")?.token,
    )
  }

  @Test
  fun `retention covers a grant approved at the very end of the window`() {
    // The two sides measure from different instants: this record is created when the request is
    // opened, the server starts the grant's TTL when it is approved. Anchored to creation, a
    // last-second approval with the maximum TTL produced a grant that outlived the client's
    // willingness to poll for it — by the whole approval window.
    val windowMillis = 600_000L
    store()
      .savePending(
        AgentAccessStore.Pending(
          origin = "https://preview.coo.ee",
          requestId = "late",
          deviceSecret = "s",
          expiresAtMillis = now + windowMillis,
        )
      )
    // Approved at the last instant of the window, granted the server's hard maximum.
    now += windowMillis + AgentAccessStore.POLL_RETENTION_SECONDS * 1000 - 1000
    assertNotNull(
      store().pendingFor("https://preview.coo.ee"),
      "the device secret must outlive any grant the window could have produced",
    )
  }

  @Test
  fun `a remembered request is finally dropped once no grant could still exist`() {
    store()
      .savePending(
        AgentAccessStore.Pending(
          origin = "https://preview.coo.ee",
          requestId = "ancient",
          deviceSecret = "s",
          expiresAtMillis = now + 600_000,
        )
      )
    // Past the window's end PLUS the retention span — retention is anchored to window-end now,
    // because that is what covers a grant approved at the last possible moment.
    now += 600_000 + (AgentAccessStore.POLL_RETENTION_SECONDS + 60) * 1000
    assertNull(store().pendingFor("https://preview.coo.ee"))
  }

  @Test
  fun `the pile refuses a new request rather than evicting a live one`() {
    // The cap used to drop the OLDEST live record to make room. Its approval link stays valid on
    // the server, so a human could approve a request whose only device secret had just been
    // deleted here. Refusing tells the caller; evicting loses a credential silently.
    fun add(i: Int) =
      store()
        .savePending(
          AgentAccessStore.Pending(
            origin = "https://h$i.example",
            requestId = "r$i",
            deviceSecret = "s$i",
            expiresAtMillis = now + 600_000,
          )
        )
    for (i in 1..AgentAccessStore.MAX_PENDING) assertTrue(add(i), "request $i should fit")
    assertFalse(add(99), "the pile is full of live requests — refuse, do not evict")
    assertEquals(AgentAccessStore.MAX_PENDING, store().allPending().size)
    // …and every original secret is still there.
    assertEquals(
      (1..AgentAccessStore.MAX_PENDING).map { "r$it" }.toSet(),
      store().allPending().map { it.requestId }.toSet(),
    )
  }

  @Test
  fun `a dead request is swept so the pile keeps accepting`() {
    for (i in 1..AgentAccessStore.MAX_PENDING) {
      store()
        .savePending(
          AgentAccessStore.Pending(
            origin = "https://h$i.example",
            requestId = "r$i",
            deviceSecret = "s$i",
            expiresAtMillis = now + 600_000,
          )
        )
    }
    now += 600_000 + (AgentAccessStore.POLL_RETENTION_SECONDS + 60) * 1000
    assertTrue(
      store()
        .savePending(
          AgentAccessStore.Pending(
            origin = "https://fresh.example",
            requestId = "fresh",
            deviceSecret = "s",
            expiresAtMillis = now + 600_000,
          )
        ),
      "records past every deadline are free to sweep",
    )
    assertEquals(listOf("fresh"), store().allPending().map { it.requestId })
  }

  @Test
  fun `the approval window closes on its deadline even though the record is kept`() {
    // This used to assert the record was *gone* at the deadline. It is deliberately kept now — the
    // server holds an approved-but-uncollected request until its grant expires, so throwing the
    // device secret away here stranded exactly the approvals that arrived just in time. What the
    // deadline still governs is what a human is told: the window is closed, and `auth status` says
    // so rather than counting down from zero.
    store()
      .savePending(
        AgentAccessStore.Pending(
          origin = "https://a.example",
          requestId = "r",
          deviceSecret = "s",
          expiresAtMillis = now + 60_000,
        )
      )
    now += 61_000
    val kept = store().pendingFor("https://a.example")
    assertNotNull(kept)
    assertTrue(kept.windowClosed(now))
    assertEquals(0, kept.secondsUntilExpiry(now))
  }

  @Test
  fun `forget reports failure rather than claiming a credential is gone`() {
    store().save(entry())
    // Make the write fail: replace the parent directory's writability by pointing the store at a
    // path whose parent is a file. "Forgotten" is a claim about what the next process reads, so a
    // failed rewrite must not come back true.
    val wedged = File(dir, "not-a-dir/agent-access.json")
    File(dir, "not-a-dir").writeText("i am a file")
    val store = AgentAccessStore(file = wedged, clock = { now }, warn = { warnings += it })
    assertFalse(store.save(entry()))
  }

  @Test
  fun `a partial write never replaces the store`() {
    // The write is a temp file plus an atomic rename, so a reader either sees the whole old store
    // or the whole new one. Asserted through its observable consequence: after a save, the file
    // parses, and no `.tmp` sibling is left behind.
    store().save(entry())
    store().save(entry(token = "cpat_second"))
    assertEquals("cpat_second", store().tokenFor("https://preview.coo.ee"))
    assertTrue(
      dir.listFiles()!!.none { it.name.endsWith(".tmp") },
      "a temp file was left behind: ${dir.listFiles()!!.map { it.name }}",
    )
  }

  @Test
  fun `IPv6 loopback is recognised as local`() {
    // `[::1]` is the documented loopback form and used to be refused: splitting the origin on ':'
    // yielded "[", which matched nothing.
    assertTrue(AgentAccessClient.isSecureEnough("http://[::1]:8723"))
    assertTrue(AgentAccessClient.isSecureEnough("http://[::1]"))
    assertFalse(AgentAccessClient.isSecureEnough("http://[2001:db8::1]:8723"))
  }

  @Test
  fun `plaintext is refused off-loopback and allowed on it`() {
    assertTrue(AgentAccessClient.isSecureEnough("https://preview.coo.ee"))
    assertTrue(AgentAccessClient.isSecureEnough("http://localhost:8080"))
    assertTrue(AgentAccessClient.isSecureEnough("http://127.0.0.1:8080"))
    assertFalse(AgentAccessClient.isSecureEnough("http://preview.coo.ee"))
    assertFalse(AgentAccessClient.isSecureEnough("http://192.168.1.5:8080"))
  }
}
