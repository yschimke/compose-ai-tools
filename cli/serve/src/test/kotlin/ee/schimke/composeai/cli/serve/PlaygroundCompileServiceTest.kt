package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * The Stage-1 compile orchestrator: staging, compile gating, preview discovery, token minting,
 * cleanup.
 */
class PlaygroundCompileServiceTest {

  private val fs = FakeFileSystem()
  private var workDirs = 0
  private val tokenStore =
    PlaygroundTokenStore(fileSystem = fs, clock = { 1_000L }, mintId = { "pg_token${workDirs}" })

  private val cmpClasspath =
    PlaygroundCompileService.Classpath("compose-m3", listOf("/catalog/app.jar".toPath()))

  private fun service(
    classpathFor: (PlaygroundMode) -> PlaygroundCompileService.Classpath? = { cmpClasspath },
    compile: (List<Path>, List<Path>, Path) -> List<PlaygroundDiagnostic> = { _, _, _ ->
      emptyList()
    },
    discover: (Path, List<Path>) -> List<String> = { _, _ -> listOf("com.example.SnippetPreview") },
    render: (PlaygroundTokenStore.PlaygroundSnippet) -> ByteArray? = { null },
    capture: (PlaygroundTokenStore.PlaygroundSnippet) -> ByteArray? = { null },
    publish: (String, ByteArray, Boolean) -> String? = { _, _, _ -> null },
    compilerOverride: PlaygroundCompileService.Compiler? = null,
    editLeasesEnabled: Boolean = false,
    editLeaseTtlMillis: Long = PlaygroundCompileService.DEFAULT_EDIT_LEASE_TTL_MILLIS,
    nowMillis: () -> Long = { 1_000L },
    scheduleEditLeaseExpiry: (Long, () -> Unit) -> (() -> Unit) = { _, _ -> {} },
  ) =
    PlaygroundCompileService(
      catalogClasspath = { mode, _ -> classpathFor(mode) },
      compiler = compilerOverride ?: PlaygroundCompileService.Compiler(compile),
      discoverer = PlaygroundCompileService.PreviewDiscoverer(discover),
      tokenStore = tokenStore,
      newWorkDir = { "/work/run${++workDirs}".toPath() },
      fileSystem = fs,
      renderFirstFrame = render,
      captureRemoteDocument = capture,
      publishRemoteDocument = publish,
      editLeasesEnabled = editLeasesEnabled,
      editLeaseTtlMillis = editLeaseTtlMillis,
      nowMillis = nowMillis,
      scheduleEditLeaseExpiry = scheduleEditLeaseExpiry,
    )

  private fun request(
    text: String = "@Preview @Composable fun P() {}",
    confType: String = "compose-cmp",
    catalog: String = "",
  ) =
    PlaygroundRunRequest(
      files = listOf(PlaygroundFile("Snippet.kt", text)),
      confType = confType,
      catalog = catalog,
    )

  @Test
  fun `a request with no catalog asks for the host's pinned default`() {
    val asked = mutableListOf<Pair<PlaygroundMode, String?>>()
    val svc =
      PlaygroundCompileService(
        catalogClasspath = { mode, catalog ->
          asked += mode to catalog
          cmpClasspath
        },
        compiler = PlaygroundCompileService.Compiler { _, _, _ -> emptyList() },
        discoverer = PlaygroundCompileService.PreviewDiscoverer { _, _ -> listOf("x") },
        tokenStore = tokenStore,
        newWorkDir = { "/work/run".toPath() },
        fileSystem = fs,
      )

    svc.run(request(), isSecurityChecked = true)
    // Blank, whitespace and absent are all "the default" — a stock kotlin-playground frontend
    // never sends the field at all.
    svc.run(request(catalog = "   "), isSecurityChecked = true)
    svc.run(request(catalog = "compose-m3"), isSecurityChecked = true)

    assertEquals(
      listOf(
        PlaygroundMode.CMP to null,
        PlaygroundMode.CMP to null,
        PlaygroundMode.CMP to "compose-m3",
      ),
      asked,
    )
  }

  @Test
  fun `a named catalog the host cannot serve is refused, never served from the default`() {
    val svc = service(classpathFor = { cmpClasspath })
    val svcWithCatalogs =
      PlaygroundCompileService(
        // The default resolves fine; the named catalog does not. Falling back would report success
        // for a design system nobody asked for.
        catalogClasspath = { _, catalog -> if (catalog == null) cmpClasspath else null },
        compiler = PlaygroundCompileService.Compiler { _, _, _ -> emptyList() },
        discoverer = PlaygroundCompileService.PreviewDiscoverer { _, _ -> listOf("x") },
        tokenStore = tokenStore,
        newWorkDir = { "/work/run".toPath() },
        fileSystem = fs,
      )

    assertNotNull(svc.run(request(), isSecurityChecked = true).previewToken)
    val resp = svcWithCatalogs.run(request(catalog = "nope"), isSecurityChecked = true)
    assertNull(resp.previewToken)
    assertEquals("catalog 'nope' cannot serve mode CMP on this host", resp.exception)
  }

  @Test
  fun `catalogChoices lists the pinned default first, then every served catalog`() {
    val svc =
      PlaygroundCompileService(
        catalogClasspath = { mode, catalog ->
          if (catalog == null && mode == PlaygroundMode.CMP) cmpClasspath else null
        },
        compiler = PlaygroundCompileService.Compiler { _, _, _ -> emptyList() },
        discoverer = PlaygroundCompileService.PreviewDiscoverer { _, _ -> listOf("x") },
        tokenStore = tokenStore,
        newWorkDir = { "/work/run".toPath() },
        fileSystem = fs,
        catalogTargets = {
          listOf(
            PlaygroundCatalogTarget("m3", "desktop", listOf(PlaygroundMode.CMP), resolved = true),
            PlaygroundCatalogTarget(
              "wear",
              "android",
              listOf(PlaygroundMode.ANDROID),
              resolved = false,
            ),
          )
        },
      )

    val choices = svc.catalogChoices()
    assertEquals(listOf("", "m3", "wear"), choices.map { it.id })
    assertEquals(listOf(PlaygroundMode.CMP), choices[0].modes)
    assertEquals("m3 (desktop)", choices[1].label)
    assertEquals(listOf(PlaygroundMode.ANDROID), choices[2].modes)
    assertEquals(listOf(true, true, false), choices.map { it.resolved })
  }

  @Test
  fun `a host with nothing pinned offers only its served catalogs`() {
    val svc =
      PlaygroundCompileService(
        catalogClasspath = { _, catalog -> if (catalog == "m3") cmpClasspath else null },
        compiler = PlaygroundCompileService.Compiler { _, _, _ -> emptyList() },
        discoverer = PlaygroundCompileService.PreviewDiscoverer { _, _ -> listOf("x") },
        tokenStore = tokenStore,
        newWorkDir = { "/work/run".toPath() },
        fileSystem = fs,
        catalogTargets = {
          listOf(
            PlaygroundCatalogTarget("m3", "desktop", listOf(PlaygroundMode.CMP), resolved = true)
          )
        },
      )

    assertEquals(emptyList(), svc.availableModes)
    assertEquals(listOf("m3"), svc.catalogChoices().map { it.id })
  }

  @Test
  fun `compilesCatalog answers for the selector's entries and for the pinned catalog`() {
    // What the browsing surfaces ask before offering a `?from=` handoff. A serve host browses far
    // more catalogs than its playground compiles, and offering the link for the rest is what made
    // "the playground doesn't work for Android" look like a playground bug: the editor opened an
    // Android preview's Kotlin and quietly retargeted it at a desktop design system.
    val svc =
      PlaygroundCompileService(
        catalogClasspath = { mode, catalog ->
          if (catalog == null && mode == PlaygroundMode.CMP) cmpClasspath else null
        },
        compiler = PlaygroundCompileService.Compiler { _, _, _ -> emptyList() },
        discoverer = PlaygroundCompileService.PreviewDiscoverer { _, _ -> listOf("x") },
        tokenStore = tokenStore,
        newWorkDir = { "/work/run".toPath() },
        fileSystem = fs,
        catalogTargets = {
          listOf(
            PlaygroundCatalogTarget("m3", "desktop", listOf(PlaygroundMode.CMP), resolved = true),
            // An Android catalog on a host with no Robolectric sidecar: served and browsable, but
            // it offers no mode, so it is not a compile target.
            PlaygroundCatalogTarget("horologist", "android", emptyList(), resolved = false),
          )
        },
        pinnedCatalogSystem = { mode ->
          if (mode == PlaygroundMode.CMP) "compose-m3" else "wear-m3"
        },
      )

    assertTrue(svc.compilesCatalog("m3"), "a selector entry with modes")
    assertFalse(svc.compilesCatalog("horologist"), "served and browsable, but no mode here")
    assertTrue(svc.compilesCatalog("compose-m3"), "the pinned default IS this catalog")
    assertFalse(
      svc.compilesCatalog("wear-m3"),
      "the Android pin's modes never resolved, so it is not in pinnedCatalogSystems",
    )
    assertFalse(svc.compilesCatalog("unknown"))
    // "" is the selector's id for the pin, not a system id — it must never match a catalog page.
    assertFalse(svc.compilesCatalog(""))
    assertEquals(setOf("compose-m3"), svc.pinnedCatalogSystems)
  }

  @Test
  fun `a host that pins a local bundle claims no catalog`() {
    // `--playground-bundle /config/x.bundle` has no system id, so nothing on the site can claim to
    // be its catalog — every `?from=` handoff is correctly withheld.
    val svc = service()
    assertEquals(emptySet(), svc.pinnedCatalogSystems)
    assertFalse(svc.compilesCatalog("compose-m3"))
  }

  @Test
  fun `a clean compile mints a token pointing at the discovered preview`() {
    var compiledClasspath: List<Path>? = null
    val svc =
      service(
        compile = { sources, cp, out ->
          compiledClasspath = cp
          assertTrue(fs.exists(sources.single()), "the snippet is staged before compile")
          assertTrue(out.toString().endsWith("classes"))
          emptyList()
        }
      )

    val resp = svc.run(request(), isSecurityChecked = true)

    val previewToken = assertNotNull(resp.previewToken)
    assertEquals("/pg/$previewToken", resp.previewUrl)
    assertNull(resp.exception)
    assertEquals(
      listOf("/catalog/app.jar".toPath()),
      compiledClasspath,
      "compiled against the catalog classpath",
    )

    val token = assertNotNull(tokenStore.get(previewToken))
    assertEquals("com.example.SnippetPreview", token.snippet.previewId)
    assertEquals(PlaygroundMode.CMP, token.snippet.mode)
    // The render classpath carries the catalog jars plus the snippet's own compiled classes.
    assertTrue(token.snippet.classesDir in token.snippet.classpath)
    assertTrue("/catalog/app.jar".toPath() in token.snippet.classpath)
    assertTrue(fs.exists(token.snippet.workDir), "the work dir is retained for the live session")
  }

  @Test
  fun `a compile error returns diagnostics under both shapes and no token, and cleans up`() {
    val error =
      PlaygroundDiagnostic(
        PlaygroundSeverity.ERROR,
        "unresolved reference: Bttun",
        "Snippet.kt",
        3,
        4,
      )
    val svc = service(compile = { _, _, _ -> listOf(error) })

    val resp = svc.run(request(), isSecurityChecked = true)

    assertNull(resp.previewToken, "no token on a compile error")
    assertEquals(listOf(error), resp.diagnostics)
    assertEquals(listOf("Snippet.kt"), resp.errors.keys.toList(), "stock errors map keyed by file")
    assertEquals(3, resp.errors.getValue("Snippet.kt").single().interval.start.line)
    assertTrue(tokenStore.snapshot().isEmpty())
    assertFalse(fs.exists("/work/run1".toPath()), "the aborted run deletes its own work dir")
  }

  @Test
  fun `warnings survive a clean compile in both diagnostics and the errors map`() {
    val warn =
      PlaygroundDiagnostic(
        PlaygroundSeverity.WARNING,
        "parameter is never used",
        "Snippet.kt",
        1,
        0,
      )
    val svc = service(compile = { _, _, _ -> listOf(warn) })

    val resp = svc.run(request(), isSecurityChecked = true)

    assertNotNull(resp.previewToken, "a warning is not an error — the token is still minted")
    assertEquals(listOf(warn), resp.diagnostics)
    assertEquals("WARNING", resp.errors.getValue("Snippet.kt").single().severity)
  }

  @Test
  fun `no @Preview is a user error with no token and cleanup`() {
    val svc = service(discover = { _, _ -> emptyList() })

    val resp = svc.run(request(), isSecurityChecked = true)

    assertNull(resp.previewToken)
    assertTrue(assertNotNull(resp.exception).contains("@Preview"))
    assertFalse(fs.exists("/work/run1".toPath()))
  }

  @Test
  fun `an unavailable mode is refused before any work dir is created`() {
    val svc = service(classpathFor = { null })

    val resp = svc.run(request(confType = "compose-android"), isSecurityChecked = true)

    assertNull(resp.previewToken)
    assertTrue(resp.exception!!.contains("ANDROID"))
    assertEquals(0, workDirs, "no work dir minted when the mode is unavailable")
  }

  @Test
  fun `blank requests are rejected`() {
    val svc = service()
    val resp =
      svc.run(
        PlaygroundRunRequest(files = listOf(PlaygroundFile("x.kt", "   "))),
        isSecurityChecked = true,
      )
    assertNotNull(resp.exception)
    assertNull(resp.previewToken)
  }

  @Test
  fun `a first-frame render is surfaced as a data URI`() {
    val svc = service(render = { byteArrayOf(1, 2, 3) })
    val resp = svc.run(request(), isSecurityChecked = true)
    assertEquals("data:image/png;base64,AQID", resp.image)
  }

  @Test
  fun `a remote-compose snippet publishes a document permalink and mints no token`() {
    var publishedName: String? = null
    var publishedChecked: Boolean? = null
    val svc =
      service(
        capture = { byteArrayOf(9, 8, 7) },
        publish = { name, bytes, checked ->
          publishedName = name
          publishedChecked = checked
          assertEquals(listOf<Byte>(9, 8, 7), bytes.toList(), "the captured bytes reach the store")
          "/d/doc123"
        },
      )

    val resp = svc.run(request(confType = "remote-compose"), isSecurityChecked = true)

    assertEquals("/d/doc123", resp.documentUrl)
    assertNull(resp.previewToken, "RC returns a document, not a live-session token")
    assertNull(resp.exception)
    assertTrue(tokenStore.snapshot().isEmpty(), "no token is minted on the RC path")
    assertFalse(
      fs.exists("/work/run1".toPath()),
      "RC needs no live session, so the work dir is released",
    )
    // The label is the preview's simple name, `.rc`-suffixed; the audit marker is forwarded.
    assertEquals("SnippetPreview.rc", publishedName)
    assertEquals(true, publishedChecked)
  }

  @Test
  fun `a remote-compose snippet that emits no document is a user error with no token and cleanup`() {
    // publish would succeed if reached — proving the failure is the absent capture, not the store.
    val svc = service(capture = { null }, publish = { _, _, _ -> "/d/never" })

    val resp = svc.run(request(confType = "remote-compose"), isSecurityChecked = true)

    assertNull(resp.documentUrl)
    assertNull(resp.previewToken)
    assertTrue(assertNotNull(resp.exception).contains("RemoteDocument"))
    assertTrue(tokenStore.snapshot().isEmpty())
    assertFalse(fs.exists("/work/run1".toPath()), "a capture-less RC run deletes its own work dir")
  }

  @Test
  fun `a captured document the store refuses returns an exception, no token, and cleanup`() {
    val svc = service(capture = { byteArrayOf(1) }, publish = { _, _, _ -> null })

    val resp = svc.run(request(confType = "remote-compose"), isSecurityChecked = true)

    assertNull(resp.documentUrl)
    assertNull(resp.previewToken)
    assertTrue(assertNotNull(resp.exception).contains("not accepted"))
    assertTrue(tokenStore.snapshot().isEmpty())
    assertFalse(fs.exists("/work/run1".toPath()))
  }

  @Test
  fun `the live modes never invoke the RC capture or publish seams`() {
    var touched = false
    val svc =
      service(
        capture = {
          touched = true
          byteArrayOf(1)
        },
        publish = { _, _, _ ->
          touched = true
          "/d/x"
        },
      )

    val resp = svc.run(request(confType = "compose-cmp"), isSecurityChecked = true)

    assertNotNull(resp.previewToken, "CMP still takes the token path")
    assertNull(resp.documentUrl)
    assertFalse(touched, "the RC seams are inert for a live-session mode")
  }

  @Test
  fun `file names are sanitised and de-duplicated`() {
    // Path components are stripped (no traversal); only the safe basename survives.
    assertEquals("passwd.kt", PlaygroundCompileService.safeKtName("../../etc/passwd"))
    assertEquals("Main.kt", PlaygroundCompileService.safeKtName("Main.kt"))
    // A name that reduces to nothing safe falls back to the default.
    assertEquals("Snippet.kt", PlaygroundCompileService.safeKtName("   "))
    assertEquals("Snippet.kt", PlaygroundCompileService.safeKtName("/////"))

    var staged: List<Path>? = null
    val svc =
      service(
        compile = { s, _, _ ->
          staged = s
          emptyList()
        }
      )
    svc.run(
      PlaygroundRunRequest(
        files = listOf(PlaygroundFile("A.kt", "fun a(){}"), PlaygroundFile("A.kt", "fun b(){}")),
        confType = "compose-cmp",
      ),
      isSecurityChecked = true,
    )
    assertEquals(
      listOf("A.kt", "A_1.kt"),
      staged!!.map { it.name },
      "colliding names are disambiguated",
    )
  }

  @Test
  fun `every file in a multi-file snippet reaches one compile, so they see each other`() {
    var staged: List<Path>? = null
    var compiles = 0
    val svc =
      service(
        compile = { sources, _, _ ->
          compiles++
          staged = sources
          sources.forEach { assertTrue(fs.exists(it), "${'$'}it should be staged before compile") }
          emptyList()
        }
      )

    val resp =
      svc.run(
        PlaygroundRunRequest(
          files =
            listOf(
              PlaygroundFile("Theme.kt", "object Palette { val brand = 1 }"),
              PlaygroundFile("Snippet.kt", "@Preview @Composable fun P() { Text(Palette.brand) }"),
            ),
          confType = "compose-cmp",
        ),
        isSecurityChecked = true,
      )

    // One compile over both sources — files are one module, not N independent compiles, which is
    // what lets Snippet.kt reference Palette from Theme.kt.
    assertEquals(1, compiles)
    assertEquals(listOf("Theme.kt", "Snippet.kt"), staged!!.map { it.name })
    assertNotNull(resp.previewToken)
  }

  @Test
  fun `the rendered preview is the same one on every run, and the rest are surfaced`() {
    // ClassGraph's scan order over the snippet's classes is not guaranteed, so the orchestrator
    // sorts: a snippet with several @Previews must not render a different one run to run.
    val svc = service(discover = { _, _ -> listOf("com.example.Zeta", "com.example.Alpha") })

    val resp = svc.run(request(), isSecurityChecked = true)

    assertEquals("com.example.Alpha", resp.previewId)
    assertEquals(listOf("com.example.Alpha", "com.example.Zeta"), resp.previews)
    assertEquals(
      resp.previewId,
      tokenStore.get(resp.previewToken!!)!!.snippet.previewId,
      "the token renders exactly the preview the response named",
    )
  }

  @Test
  fun `the remote-compose terminal names its preview too`() {
    val svc =
      service(
        discover = { _, _ -> listOf("com.example.Doc") },
        capture = { byteArrayOf(1, 2) },
        publish = { _, _, _ -> "/d/abc" },
      )

    val resp = svc.run(request(confType = "remote-compose"), isSecurityChecked = true)

    assertEquals("/d/abc", resp.documentUrl)
    assertEquals("com.example.Doc", resp.previewId)
    assertEquals(listOf("com.example.Doc"), resp.previews)
  }

  @Test
  fun `case-only-distinct names are disambiguated so a case-insensitive FS can't overwrite`() {
    var staged: List<Path>? = null
    val svc =
      service(
        compile = { s, _, _ ->
          staged = s
          emptyList()
        }
      )
    svc.run(
      PlaygroundRunRequest(
        files = listOf(PlaygroundFile("A.kt", "fun a(){}"), PlaygroundFile("a.kt", "fun b(){}")),
        confType = "compose-cmp",
      ),
      isSecurityChecked = true,
    )
    assertEquals(listOf("A.kt", "a_1.kt"), staged!!.map { it.name })
  }

  @Test
  fun `a work-dir allocation failure returns the JSON contract, not a throw`() {
    val svc =
      PlaygroundCompileService(
        catalogClasspath = { _, _ -> cmpClasspath },
        compiler = PlaygroundCompileService.Compiler { _, _, _ -> emptyList() },
        discoverer = PlaygroundCompileService.PreviewDiscoverer { _, _ -> listOf("x") },
        tokenStore = tokenStore,
        newWorkDir = { throw java.io.IOException("no space left on device") },
        fileSystem = fs,
      )

    val resp = svc.run(request(), isSecurityChecked = true)

    assertNotNull(
      resp.exception,
      "a temp-volume failure is the JSON contract, not an escaped throw",
    )
    assertNull(resp.previewToken)
    assertTrue(tokenStore.snapshot().isEmpty())
  }

  @Test
  fun `one authenticated editing lease is exclusive, renewable, and owner-released`() {
    var now = 1_000L
    val svc =
      service(
        editLeasesEnabled = true,
        editLeaseTtlMillis = 5_000L,
        nowMillis = { now },
      )

    val alice = svc.acquireEditLease("alice")
    assertTrue(alice.acquired)
    val aliceLease = assertNotNull(alice.lease)
    assertEquals(6_000L, alice.expiresAtEpochMs)

    val bob = svc.acquireEditLease("bob")
    assertFalse(bob.acquired)
    assertNull(bob.lease, "a busy response never discloses the capability")
    assertFalse(svc.releaseEditLease("bob", aliceLease), "ownership is checked")

    now = 2_000L
    val renewed = svc.acquireEditLease("alice")
    assertEquals(alice.lease, renewed.lease)
    assertEquals(7_000L, renewed.expiresAtEpochMs)
    assertTrue(svc.releaseEditLease("alice", aliceLease))
    assertTrue(svc.acquireEditLease("bob").acquired, "release makes the single slot available")
    now = 8_000L
    assertFalse(svc.editLeaseHealth().active, "status observes idle expiry without taking the lock")
  }

  @Test
  fun `renewing a lease cancels the superseded expiry task`() {
    var scheduled = 0
    var cancelled = 0
    val svc =
      service(
        editLeasesEnabled = true,
        scheduleEditLeaseExpiry = { _, _ ->
          scheduled++
          { cancelled++ }
        },
      )

    val lease = svc.acquireEditLease("alice", client = "tab-a").lease!!
    svc.acquireEditLease("alice", client = "tab-a")

    assertEquals(2, scheduled)
    assertEquals(1, cancelled, "renewal removes the old delayed task")
    assertTrue(svc.releaseEditLease("alice", lease, client = "tab-a"))
    assertEquals(2, cancelled, "release removes the active delayed task")
  }

  @Test
  fun `one tab release retains a lease held by another tab`() {
    val svc = service(editLeasesEnabled = true)
    val first = svc.acquireEditLease("alice", client = "tab-a")
    val second = svc.acquireEditLease("alice", client = "tab-b")
    val lease = assertNotNull(first.lease)

    assertEquals(first.lease, second.lease)
    assertTrue(svc.releaseEditLease("alice", lease, client = "tab-a"))
    assertTrue(svc.editLeaseHealth().active)
    assertTrue(fs.metadataOrNull("/work/run1".toPath())?.isDirectory == true)

    assertTrue(svc.releaseEditLease("alice", lease, client = "tab-b"))
    assertFalse(svc.editLeaseHealth().active)
    assertNull(fs.metadataOrNull("/work/run1".toPath()))
  }

  @Test
  fun `reattaching a tab reports the accepted server revision`() {
    val svc = service(editLeasesEnabled = true)
    val lease = assertNotNull(svc.acquireEditLease("alice", client = "tab-a").lease)
    val response =
      svc.run(
        request().copy(editLease = lease, revision = 7),
        isSecurityChecked = true,
        authenticatedOwner = "alice",
      )
    assertNotNull(response.previewToken)

    val reattached = svc.acquireEditLease("alice", client = "tab-b")
    assertEquals(lease, reattached.lease)
    assertEquals(7, reattached.revision)
  }

  @Test
  fun `last accepted revision survives lease expiry and release`() {
    var now = 1_000L
    val svc =
      service(
        editLeasesEnabled = true,
        editLeaseTtlMillis = 5_000L,
        nowMillis = { now },
      )
    val alice = assertNotNull(svc.acquireEditLease("alice").lease)
    val response =
      svc.run(
        request().copy(editLease = alice, revision = 7),
        isSecurityChecked = true,
        authenticatedOwner = "alice",
      )
    assertNotNull(response.previewToken)

    now = 6_001L
    val bob = assertNotNull(svc.acquireEditLease("bob").lease) // Purges Alice's expired lease.
    assertEquals(7, svc.editLeaseHealth().lastRevision)

    assertTrue(svc.releaseEditLease("bob", bob))
    val completed = svc.editLeaseHealth()
    assertFalse(completed.active)
    assertEquals(7, completed.lastRevision)
  }

  @Test
  fun `an abandoned editing lease deletes its workspace at the idle deadline`() {
    var now = 1_000L
    val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    val svc =
      service(
        editLeasesEnabled = true,
        editLeaseTtlMillis = 5_000L,
        nowMillis = { now },
        scheduleEditLeaseExpiry = { delay, task ->
          scheduled += delay to task
          {}
        },
      )

    val lease = svc.acquireEditLease("alice")
    assertTrue(lease.acquired)
    assertTrue(fs.metadataOrNull("/work/run1".toPath())?.isDirectory == true)
    assertEquals(5_000L, scheduled.single().first)

    now = 6_000L
    scheduled.single().second.invoke()

    assertFalse(svc.editLeaseHealth().active)
    assertNull(fs.metadataOrNull("/work/run1".toPath()), "expiry reclaims the IC workspace")
  }

  @Test
  fun `leased compile refreshes the idle deadline after work finishes`() {
    var now = 1_000L
    val compiler =
      object : PlaygroundCompileService.Compiler {
        override fun compile(
          sources: List<Path>,
          classpath: List<Path>,
          outputDir: Path,
        ) = emptyList<PlaygroundDiagnostic>()

        override fun compileIncremental(
          sources: List<Path>,
          classpath: List<Path>,
          outputDir: Path,
          workingDir: Path,
          modified: List<Path>,
          removed: List<Path>,
          firstBuild: Boolean,
        ): PlaygroundCompileService.IncrementalCompileResult {
          now = 8_000L // The compile outlives the deadline established when it started.
          fs.createDirectories(outputDir)
          fs.write(outputDir / "Snippet.class") { writeUtf8("compiled") }
          return PlaygroundCompileService.IncrementalCompileResult(emptyList(), true)
        }
      }
    val svc =
      service(
        compilerOverride = compiler,
        editLeasesEnabled = true,
        editLeaseTtlMillis = 5_000L,
        nowMillis = { now },
      )
    val lease = svc.acquireEditLease("alice").lease!!

    svc.run(
      request().copy(editLease = lease, revision = 1),
      isSecurityChecked = true,
      authenticatedOwner = "alice",
    )

    val health = svc.editLeaseHealth()
    assertTrue(health.active)
    assertEquals(13_000L, health.expiresAtEpochMs)
  }

  @Test
  fun `leased revisions send precise source changes and mint immutable class snapshots`() {
    data class Call(
      val modified: List<String>,
      val removed: List<String>,
      val first: Boolean,
    )
    val calls = mutableListOf<Call>()
    var generation = 0
    val compiler =
      object : PlaygroundCompileService.Compiler {
        override fun compile(
          sources: List<Path>,
          classpath: List<Path>,
          outputDir: Path,
        ): List<PlaygroundDiagnostic> = emptyList()

        override fun compileIncremental(
          sources: List<Path>,
          classpath: List<Path>,
          outputDir: Path,
          workingDir: Path,
          modified: List<Path>,
          removed: List<Path>,
          firstBuild: Boolean,
        ): PlaygroundCompileService.IncrementalCompileResult {
          calls += Call(modified.map { it.name }, removed.map { it.name }, firstBuild)
          fs.createDirectories(outputDir)
          fs.write(outputDir / "Snippet.class") { writeUtf8("generation-${++generation}") }
          return PlaygroundCompileService.IncrementalCompileResult(emptyList(), true)
        }
      }
    val svc = service(compilerOverride = compiler, editLeasesEnabled = true)
    val lease = svc.acquireEditLease("alice").lease!!

    fun leased(revision: Long, files: List<PlaygroundFile>) =
      svc.run(
        PlaygroundRunRequest(
          files = files,
          confType = "compose-cmp",
          editLease = lease,
          revision = revision,
        ),
        isSecurityChecked = true,
        authenticatedOwner = "alice",
      )

    val first =
      leased(
        1,
        listOf(
          PlaygroundFile("Snippet.kt", "@Preview fun P() = Unit"),
          PlaygroundFile("Theme.kt", "object Theme"),
        ),
      )
    val second = leased(2, listOf(PlaygroundFile("Snippet.kt", "@Preview fun P() = println(2)")))

    assertEquals(Call(listOf("Snippet.kt", "Theme.kt"), emptyList(), true), calls[0])
    assertEquals(Call(listOf("Snippet.kt"), listOf("Theme.kt"), false), calls[1])
    assertEquals(1L, first.revision)
    assertEquals(2L, second.revision)
    assertTrue(first.incremental)
    val firstClasses = tokenStore.get(first.previewToken!!)!!.snippet.classesDir
    val secondClasses = tokenStore.get(second.previewToken!!)!!.snippet.classesDir
    assertTrue(firstClasses != secondClasses, "each token owns an immutable revision snapshot")
    assertEquals("generation-1", fs.read(firstClasses / "Snippet.class") { readUtf8() })
    assertEquals("generation-2", fs.read(secondClasses / "Snippet.class") { readUtf8() })
    val health = svc.editLeaseHealth()
    assertTrue(health.active)
    assertEquals(1, health.acquisitions)
    assertEquals(2, health.compileAttempts)
    assertEquals(2, health.incrementalCompiles)
    assertEquals(0, health.fullFallbacks)
  }

  @Test
  fun `busy compiler does not accept files or revision and retries the full dirty set`() {
    data class Call(val modified: List<String>, val firstBuild: Boolean)

    val calls = mutableListOf<Call>()
    var attempt = 0
    val compiler =
      object : PlaygroundCompileService.Compiler {
        override fun compile(
          sources: List<Path>,
          classpath: List<Path>,
          outputDir: Path,
        ) = emptyList<PlaygroundDiagnostic>()

        override fun compileIncremental(
          sources: List<Path>,
          classpath: List<Path>,
          outputDir: Path,
          workingDir: Path,
          modified: List<Path>,
          removed: List<Path>,
          firstBuild: Boolean,
        ): PlaygroundCompileService.IncrementalCompileResult {
          calls += Call(modified.map { it.name }, firstBuild)
          attempt++
          if (attempt == 1) {
            return PlaygroundCompileService.IncrementalCompileResult(
              diagnostics =
                listOf(
                  PlaygroundDiagnostic(
                    PlaygroundSeverity.ERROR,
                    "the playground is busy compiling (all 1 compile slots in use) — try again shortly",
                  )
                ),
              incremental = false,
            )
          }
          fs.createDirectories(outputDir)
          fs.write(outputDir / "Snippet.class") { writeUtf8("compiled") }
          return PlaygroundCompileService.IncrementalCompileResult(emptyList(), false)
        }
      }
    val svc = service(compilerOverride = compiler, editLeasesEnabled = true)
    val lease = svc.acquireEditLease("alice").lease!!
    val edit = request(text = "@Preview fun P() = println(1)").copy(editLease = lease)

    val busy = svc.run(edit.copy(revision = 1), true, authenticatedOwner = "alice")
    assertTrue(busy.diagnostics.single().message.startsWith("the playground is busy"))
    assertNull(busy.revision)
    assertNull(svc.editLeaseHealth().lastRevision)

    val retried = svc.run(edit.copy(revision = 2), true, authenticatedOwner = "alice")
    assertNotNull(retried.previewToken)
    assertEquals(
      listOf(Call(listOf("Snippet.kt"), true), Call(listOf("Snippet.kt"), true)),
      calls,
      "the rejected attempt does not become the source or IC baseline",
    )
    assertEquals(2, svc.editLeaseHealth().lastRevision)
  }

  @Test
  fun `busy compiler restores the last accepted source tree`() {
    data class Call(
      val modified: List<String>,
      val removed: List<String>,
      val contents: Map<String, String>,
    )

    val calls = mutableListOf<Call>()
    val compiler =
      object : PlaygroundCompileService.Compiler {
        override fun compile(
          sources: List<Path>,
          classpath: List<Path>,
          outputDir: Path,
        ) = emptyList<PlaygroundDiagnostic>()

        override fun compileIncremental(
          sources: List<Path>,
          classpath: List<Path>,
          outputDir: Path,
          workingDir: Path,
          modified: List<Path>,
          removed: List<Path>,
          firstBuild: Boolean,
        ): PlaygroundCompileService.IncrementalCompileResult {
          calls +=
            Call(
              modified.map { it.name },
              removed.map { it.name },
              sources.associate { it.name to fs.read(it) { readUtf8() } },
            )
          if (calls.size == 2) {
            return PlaygroundCompileService.IncrementalCompileResult(
              listOf(
                PlaygroundDiagnostic(
                  PlaygroundSeverity.ERROR,
                  "the playground is busy compiling (all 1 compile slots in use) — try again shortly",
                )
              ),
              incremental = false,
            )
          }
          fs.createDirectories(outputDir)
          fs.write(outputDir / "Snippet.class") { writeUtf8("compiled") }
          return PlaygroundCompileService.IncrementalCompileResult(emptyList(), false)
        }
      }
    val svc = service(compilerOverride = compiler, editLeasesEnabled = true)
    val lease = svc.acquireEditLease("alice").lease!!
    fun run(revision: Long, files: List<PlaygroundFile>) =
      svc.run(
        request(text = files.first().text)
          .copy(
            files = files,
            editLease = lease,
            revision = revision,
          ),
        true,
        authenticatedOwner = "alice",
      )

    val accepted =
      listOf(
        PlaygroundFile("Snippet.kt", "@Preview fun P() = println(1)"),
        PlaygroundFile("Theme.kt", "object Theme"),
      )
    assertNotNull(run(1, accepted).previewToken)
    val busy = run(2, listOf(PlaygroundFile("Snippet.kt", "@Preview fun P() = println(2)")))
    assertTrue(busy.diagnostics.single().message.startsWith("the playground is busy"))

    assertNotNull(run(3, accepted).previewToken)
    assertEquals(
      Call(emptyList(), emptyList(), accepted.associate { it.name to it.text }),
      calls[2],
      "the retry sees the accepted files on disk even though its desired map is unchanged",
    )
  }

  @Test
  fun `stale or foreign leased revisions are rejected before compilation`() {
    var compiles = 0
    val compiler =
      object : PlaygroundCompileService.Compiler {
        override fun compile(
          sources: List<Path>,
          classpath: List<Path>,
          outputDir: Path,
        ) = emptyList<PlaygroundDiagnostic>()

        override fun compileIncremental(
          sources: List<Path>,
          classpath: List<Path>,
          outputDir: Path,
          workingDir: Path,
          modified: List<Path>,
          removed: List<Path>,
          firstBuild: Boolean,
        ): PlaygroundCompileService.IncrementalCompileResult {
          compiles++
          fs.createDirectories(outputDir)
          return PlaygroundCompileService.IncrementalCompileResult(emptyList(), true)
        }
      }
    val svc = service(compilerOverride = compiler, editLeasesEnabled = true)
    val lease = svc.acquireEditLease("alice").lease!!
    val req = request().copy(editLease = lease, revision = 1)

    assertTrue(svc.run(req, true, authenticatedOwner = "bob").exception!!.contains("not yours"))
    svc.run(req, true, authenticatedOwner = "alice")
    assertTrue(svc.run(req, true, authenticatedOwner = "alice").exception!!.contains("stale"))
    assertEquals(1, compiles)
  }

  @Test
  fun `an incremental infrastructure failure retries once through full compile`() {
    var fullCompiles = 0
    val compiler =
      object : PlaygroundCompileService.Compiler {
        override fun compile(
          sources: List<Path>,
          classpath: List<Path>,
          outputDir: Path,
        ): List<PlaygroundDiagnostic> {
          fullCompiles++
          fs.createDirectories(outputDir)
          fs.write(outputDir / "Snippet.class") { writeUtf8("full") }
          return emptyList()
        }

        override fun compileIncremental(
          sources: List<Path>,
          classpath: List<Path>,
          outputDir: Path,
          workingDir: Path,
          modified: List<Path>,
          removed: List<Path>,
          firstBuild: Boolean,
        ) =
          PlaygroundCompileService.IncrementalCompileResult(
            listOf(
              PlaygroundDiagnostic(
                PlaygroundSeverity.ERROR,
                "compilation failed: broken IC cache",
              )
            ),
            true,
          )
      }
    val svc = service(compilerOverride = compiler, editLeasesEnabled = true)
    val lease = svc.acquireEditLease("alice").lease!!
    val response =
      svc.run(
        request().copy(editLease = lease, revision = 1),
        isSecurityChecked = true,
        authenticatedOwner = "alice",
      )

    assertEquals(1, fullCompiles)
    assertFalse(response.incremental)
    assertNotNull(response.previewToken)
    assertEquals(1, svc.editLeaseHealth().fullFallbacks)
  }

  @Test
  fun `an unexpected leased compiler failure still returns the JSON contract`() {
    val compiler =
      object : PlaygroundCompileService.Compiler {
        override fun compile(
          sources: List<Path>,
          classpath: List<Path>,
          outputDir: Path,
        ) = emptyList<PlaygroundDiagnostic>()

        override fun compileIncremental(
          sources: List<Path>,
          classpath: List<Path>,
          outputDir: Path,
          workingDir: Path,
          modified: List<Path>,
          removed: List<Path>,
          firstBuild: Boolean,
        ): PlaygroundCompileService.IncrementalCompileResult = error("compiler vanished")
      }
    val svc = service(compilerOverride = compiler, editLeasesEnabled = true)
    val lease = svc.acquireEditLease("alice").lease!!

    val response =
      svc.run(
        request().copy(editLease = lease, revision = 1),
        isSecurityChecked = true,
        authenticatedOwner = "alice",
      )

    assertTrue(response.exception!!.contains("compiler vanished"))
    assertNull(response.previewToken)
  }
}
