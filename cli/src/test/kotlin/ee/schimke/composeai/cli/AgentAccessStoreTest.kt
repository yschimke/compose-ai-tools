package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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
  fun `plaintext is refused off-loopback and allowed on it`() {
    assertTrue(AgentAccessClient.isSecureEnough("https://preview.coo.ee"))
    assertTrue(AgentAccessClient.isSecureEnough("http://localhost:8080"))
    assertTrue(AgentAccessClient.isSecureEnough("http://127.0.0.1:8080"))
    assertFalse(AgentAccessClient.isSecureEnough("http://preview.coo.ee"))
    assertFalse(AgentAccessClient.isSecureEnough("http://192.168.1.5:8080"))
  }
}
