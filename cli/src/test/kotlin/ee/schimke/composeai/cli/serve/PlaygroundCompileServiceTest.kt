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
  ) =
    PlaygroundCompileService(
      catalogClasspath = classpathFor,
      compiler = PlaygroundCompileService.Compiler(compile),
      discoverer = PlaygroundCompileService.PreviewDiscoverer(discover),
      tokenStore = tokenStore,
      newWorkDir = { "/work/run${++workDirs}".toPath() },
      fileSystem = fs,
      renderFirstFrame = render,
      captureRemoteDocument = capture,
      publishRemoteDocument = publish,
    )

  private fun request(
    text: String = "@Preview @Composable fun P() {}",
    confType: String = "compose-cmp",
  ) = PlaygroundRunRequest(files = listOf(PlaygroundFile("Snippet.kt", text)), confType = confType)

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

    assertNotNull(resp.previewToken)
    assertEquals("/pg/${resp.previewToken}", resp.previewUrl)
    assertNull(resp.exception)
    assertEquals(
      listOf("/catalog/app.jar".toPath()),
      compiledClasspath,
      "compiled against the catalog classpath",
    )

    val token = tokenStore.get(resp.previewToken!!)!!
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
    assertNotNull(resp.exception)
    assertTrue(resp.exception!!.contains("@Preview"))
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
    assertNotNull(resp.exception)
    assertTrue(resp.exception!!.contains("RemoteDocument"))
    assertTrue(tokenStore.snapshot().isEmpty())
    assertFalse(fs.exists("/work/run1".toPath()), "a capture-less RC run deletes its own work dir")
  }

  @Test
  fun `a captured document the store refuses returns an exception, no token, and cleanup`() {
    val svc = service(capture = { byteArrayOf(1) }, publish = { _, _, _ -> null })

    val resp = svc.run(request(confType = "remote-compose"), isSecurityChecked = true)

    assertNull(resp.documentUrl)
    assertNull(resp.previewToken)
    assertNotNull(resp.exception)
    assertTrue(resp.exception!!.contains("not accepted"))
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
        catalogClasspath = { cmpClasspath },
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
}
