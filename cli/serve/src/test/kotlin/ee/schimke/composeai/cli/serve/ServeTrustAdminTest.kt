package ee.schimke.composeai.cli.serve

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.jupiter.api.Test

class ServeTrustAdminTest {

  private fun fixture(
    initial: TrustStore = TrustStore.EMPTY
  ): Triple<ServeTrustAdmin, MutableTrustStore, ServeTrustStoreFile> {
    val fs = FakeFileSystem()
    fs.createDirectories("/config".toPath())
    val file = ServeTrustStoreFile("/config/producers.json".toPath(), fs)
    if (initial != TrustStore.EMPTY) file.save(initial)
    val store = MutableTrustStore(initial)
    return Triple(ServeTrustAdmin(store, file, onLog = {}), store, file)
  }

  @Test
  fun `an added branch is trusted immediately and written to the file`() {
    val (admin, store, file) = fixture()
    assertFalse(store.get().trustsBranch("yschimke/horologist", "design-artifacts/horologist"))

    val result =
      admin.add(
        AdminTrustEntry(
          kind = "branch",
          repo = "yschimke/horologist",
          branch = "design-artifacts/*",
        )
      )

    assertTrue(result is ServeTrustAdmin.Result.Ok)
    assertNull(result.warning)
    // The point of the change: in force on the running server, no restart.
    assertTrue(store.get().trustsBranch("yschimke/horologist", "design-artifacts/horologist"))
    // ...and durable, so it survives the next roll.
    assertTrue(file.load().trustsBranch("yschimke/horologist", "design-artifacts/horologist"))
  }

  @Test
  fun `a removed branch stops being trusted and leaves the rest of the store intact`() {
    val (admin, store, file) =
      fixture(
        TrustStore(
          branches =
            listOf(
              TrustedBranch("yschimke/horologist", "design-artifacts/*"),
              TrustedBranch("yschimke/compose-ai-tools", "design-artifacts/*"),
            )
        )
      )

    val result =
      admin.remove(
        AdminTrustEntry(
          kind = "branch",
          repo = "yschimke/horologist",
          branch = "design-artifacts/*",
        )
      )

    assertTrue(result is ServeTrustAdmin.Result.Ok)
    assertFalse(store.get().trustsBranch("yschimke/horologist", "design-artifacts/horologist"))
    assertTrue(
      file.load().trustsBranch("yschimke/compose-ai-tools", "design-artifacts/compose-m3"),
      "removing one producer must not disturb the others",
    )
  }

  @Test
  fun `adding the same branch twice is a conflict, not a duplicate entry`() {
    val (admin, _, file) = fixture()
    val entry =
      AdminTrustEntry(kind = "branch", repo = "yschimke/horologist", branch = "design-artifacts/*")

    assertTrue(admin.add(entry) is ServeTrustAdmin.Result.Ok)
    assertTrue(admin.add(entry) is ServeTrustAdmin.Result.Conflict)
    assertEquals(1, file.load().branches.size)
  }

  @Test
  fun `removing a producer that is not trusted is a conflict`() {
    val (admin, _, _) = fixture()
    val result = admin.remove(AdminTrustEntry(kind = "branch", repo = "nobody/nothing"))
    assertTrue(result is ServeTrustAdmin.Result.Conflict)
  }

  @Test
  fun `a match-everything repo pattern is refused`() {
    val (admin, store, _) = fixture()
    for (pattern in listOf("*/*", "*/**", "**/*")) {
      val result = admin.add(AdminTrustEntry(kind = "branch", repo = pattern, branch = "*"))
      assertTrue(result is ServeTrustAdmin.Result.Invalid, "expected '$pattern' to be refused")
    }
    // Nothing leaked into the live store — with --allow-render-trusted this would be RCE for
    // anyone.
    assertTrue(store.get().branches.isEmpty())
  }

  @Test
  fun `a malformed repo pattern is refused`() {
    val (admin, _, _) = fixture()
    val result = admin.add(AdminTrustEntry(kind = "branch", repo = "no-slash-here"))
    assertTrue(result is ServeTrustAdmin.Result.Invalid)
  }

  @Test
  fun `an unknown kind is refused rather than silently ignored`() {
    val (admin, _, _) = fixture()
    assertTrue(admin.add(AdminTrustEntry(kind = "banana")) is ServeTrustAdmin.Result.Invalid)
    assertTrue(admin.remove(AdminTrustEntry(kind = "banana")) is ServeTrustAdmin.Result.Invalid)
  }

  @Test
  fun `a key with unparseable material is refused`() {
    val (admin, _, _) = fixture()
    val result =
      admin.add(AdminTrustEntry(kind = "key", keyId = "ci", publicKey = "not-a-real-key"))
    assertTrue(result is ServeTrustAdmin.Result.Invalid)
  }

  @Test
  fun `an oidc identity round-trips`() {
    val (admin, store, file) = fixture()
    val identity = "repo:yschimke/compose-ai-tools:ref:refs/heads/main"

    assertTrue(
      admin.add(AdminTrustEntry(kind = "oidc", identity = identity)) is ServeTrustAdmin.Result.Ok
    )

    assertTrue(store.get().trustsIdentity(identity))
    assertEquals(listOf(identity), file.load().oidc.map { it.identity })
  }

  @Test
  fun `a hand-edit between admin calls is not clobbered`() {
    val (admin, _, file) = fixture()
    admin.add(AdminTrustEntry(kind = "branch", repo = "a/one", branch = "design-artifacts/*"))
    // The operator edits producers.json directly while the server runs.
    file.save(file.load().copy(oidc = listOf(TrustedIdentity("repo:a/one:ref:refs/heads/main"))))

    admin.add(AdminTrustEntry(kind = "branch", repo = "b/two", branch = "design-artifacts/*"))

    val onDisk = file.load()
    assertEquals(2, onDisk.branches.size)
    assertEquals(1, onDisk.oidc.size, "the hand-added identity must survive the next admin write")
  }

  @Test
  fun `with no file configured the change applies but is reported as unpersisted`() {
    val store = MutableTrustStore()
    val admin = ServeTrustAdmin(store, file = null, onLog = {})

    val result =
      admin.add(AdminTrustEntry(kind = "branch", repo = "a/one", branch = "design-artifacts/*"))

    val ok = result as ServeTrustAdmin.Result.Ok
    assertTrue(assertNotNull(ok.warning).contains("not persisted"))
    assertTrue(store.get().trustsBranch("a/one", "design-artifacts/x"))
  }

  @Test
  fun `a direct file edit is picked up without an admin call or a restart`() {
    val fs = FakeFileSystem()
    fs.createDirectories("/config".toPath())
    val file = ServeTrustStoreFile("/config/producers.json".toPath(), fs)
    file.save(TrustStore(branches = listOf(TrustedBranch("a/one", "design-artifacts/*"))))
    val store = MutableTrustStore(file.load(), source = file, onLog = {})
    assertFalse(store.get().trustsBranch("b/two", "design-artifacts/x"))

    // The operator edits the mounted producers.json by hand. No admin call, no restart.
    file.save(
      TrustStore(
        branches =
          listOf(
            TrustedBranch("a/one", "design-artifacts/*"),
            TrustedBranch("b/two", "design-artifacts/*"),
          )
      )
    )

    assertTrue(store.get().trustsBranch("b/two", "design-artifacts/x"))
  }

  @Test
  fun `an unreadable file keeps the last good store rather than un-trusting everything`() {
    val fs = FakeFileSystem()
    fs.createDirectories("/config".toPath())
    val path = "/config/producers.json".toPath()
    val file = ServeTrustStoreFile(path, fs)
    file.save(TrustStore(branches = listOf(TrustedBranch("a/one", "design-artifacts/*"))))
    val store = MutableTrustStore(file.load(), source = file, onLog = {})

    // A half-written edit. Dropping to EMPTY here would un-trust every catalog on the box
    // mid-flight.
    fs.write(path) { writeUtf8("{ not json") }

    assertTrue(store.get().trustsBranch("a/one", "design-artifacts/x"))
  }

  @Test
  fun `an admin mutation refuses to rewrite an unreadable trust document`() {
    val fs = FakeFileSystem()
    fs.createDirectories("/config".toPath())
    val path = "/config/producers.json".toPath()
    val file = ServeTrustStoreFile(path, fs)
    file.save(TrustStore(branches = listOf(TrustedBranch("a/one", "design-artifacts/*"))))
    val admin = ServeTrustAdmin(MutableTrustStore(file.load()), file, onLog = {})
    fs.write(path) { writeUtf8("{ truncated") }

    val result = admin.add(AdminTrustEntry(kind = "branch", repo = "b/two"))

    // Saving over it would resurrect stale entries the operator may be mid-way through removing.
    assertTrue(result is ServeTrustAdmin.Result.Invalid, result.toString())
    assertEquals("{ truncated", fs.read(path) { readUtf8() }, "the bad file must be left alone")
  }

  @Test
  fun `revoking trust fires the revocation hook with the reduced store`() {
    val (_, _, file) =
      fixture(TrustStore(branches = listOf(TrustedBranch("a/one", "design-artifacts/*"))))
    val revoked = mutableListOf<TrustStore>()
    val admin =
      ServeTrustAdmin(
        MutableTrustStore(file.load()),
        file,
        onRevoke = { revoked += it },
        onLog = {},
      )

    admin.remove(AdminTrustEntry(kind = "branch", repo = "a/one", branch = "design-artifacts/*"))

    assertEquals(1, revoked.size, "a removal must trigger cleanup of what that trust was buying")
    assertFalse(revoked.single().trustsBranch("a/one", "design-artifacts/x"))
  }

  @Test
  fun `adding trust does not fire the revocation hook`() {
    val (_, _, file) = fixture()
    var fired = 0
    val admin =
      ServeTrustAdmin(MutableTrustStore(file.load()), file, onRevoke = { fired++ }, onLog = {})

    admin.add(AdminTrustEntry(kind = "branch", repo = "a/one", branch = "design-artifacts/*"))

    assertEquals(0, fired)
  }

  @Test
  fun `concurrent additions all survive in the trust file`() {
    val (admin, _, file) = fixture()
    val threads =
      (1..8).map { i ->
        Thread {
          admin.add(
            AdminTrustEntry(kind = "branch", repo = "owner/repo$i", branch = "design-artifacts/*")
          )
        }
      }
    threads.forEach { it.start() }
    threads.forEach { it.join() }

    // Without serialising the whole load-modify-save, writers would clobber each other and only a
    // subset would reach disk.
    assertEquals(8, file.load().branches.size)
  }
}
