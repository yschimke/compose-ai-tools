package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.bundle.coordinates.CoordinateResolver
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient

/**
 * `COMPOSE_PREVIEW_NO_NETWORK` (#5960): with the switch on, every outbound path refuses at its
 * entry, before a connection is opened or a process spawned. Each test forces the switch through
 * [NetworkPolicy.override] (or a path's own `noNetwork` parameter), never the real environment, and
 * proves nothing was attempted: the fetch seam was not called, or the destination is an unroutable
 * address a real attempt would hang on.
 */
class NetworkPolicyTest {
  private val tmp = Files.createTempDirectory("network-policy-test-").toFile()

  /** RFC 5737 TEST-NET-1: never routable, so a real attempt would time out, not refuse fast. */
  private val unroutable = "https://192.0.2.1"

  @AfterTest
  fun cleanup() {
    NetworkPolicy.override = null
    tmp.deleteRecursively()
  }

  private fun refusedFetcher(count: AtomicInteger) = { _: String, _: File ->
    count.incrementAndGet()
    error("the network was reached")
  }

  private fun assertRefusal(e: NetworkDisabledException) {
    assertTrue(e.message!!.contains(NetworkPolicy.ENV), e.message)
    assertTrue(e.message!!.contains("needs the network"), e.message)
    assertTrue(e.remedy.isNotBlank())
  }

  // -- the switch
  // ----------------------------------------------------------------------------------

  @Test
  fun `the switch reads the environment variable and the system property`() {
    fun disabled(env: String?, prop: String?) =
      NetworkPolicy.isDisabled(
        env = { if (it == NetworkPolicy.ENV) env else null },
        property = { if (it == NetworkPolicy.PROPERTY) prop else null },
      )
    assertTrue(disabled("1", null))
    assertTrue(disabled("true", null))
    assertTrue(disabled(null, "off"))
    assertTrue(disabled(null, "OFF"))
    assertFalse(disabled(null, null))
    assertFalse(disabled("0", null))
    assertFalse(disabled(null, "on"))
  }

  @Test
  fun `the switch implies offline, and loopback is not outbound`() {
    NetworkPolicy.withOverride(true) {
      assertTrue(NetworkPolicy.disabled)
      assertTrue(NetworkPolicy.offline)
    }
    assertTrue(NetworkPolicy.isLoopback("http://localhost:8080/x"))
    assertTrue(NetworkPolicy.isLoopback("http://127.0.0.1:1"))
    assertTrue(NetworkPolicy.isLoopback("http://[::1]:9"))
    assertFalse(NetworkPolicy.isLoopback("https://example.com"))
    assertFalse(NetworkPolicy.isLoopback("not a url"))
    NetworkPolicy.requireForUrl("http://localhost:1", "x", "y", disabled = true)
    assertFailsWith<NetworkDisabledException> {
      NetworkPolicy.requireForUrl(unroutable, "x", "y", disabled = true)
    }
  }

  @Test
  fun `daemons and Gradle get the offline flags only under the switch`() {
    assertEquals(
      mapOf("composeai.fonts.offline" to "true"),
      NetworkPolicy.daemonSystemProperties(true),
    )
    assertEquals(emptyMap(), NetworkPolicy.daemonSystemProperties(false))
    assertEquals(
      listOf("--offline", "-PcomposePreview.fontsOffline=true"),
      NetworkPolicy.gradleArguments(true),
    )
    assertEquals(emptyList(), NetworkPolicy.gradleArguments(false))
  }

  // -- run-time provisioning
  // -----------------------------------------------------------------------

  @Test
  fun `daemon sidecar fetch refuses before downloading`() {
    val fetches = AtomicInteger()
    val e =
      assertFailsWith<NetworkDisabledException> {
        DaemonSidecarProvision.ensure(
          DaemonSidecarProvision.Sidecar.DESKTOP,
          version = "9.9.9",
          cacheRoot = tmp,
          offline = false,
          fetcher = DaemonSidecarProvision.Fetcher(refusedFetcher(fetches)),
          log = {},
          noNetwork = true,
        )
      }
    assertRefusal(e)
    assertTrue(e.remedy.contains("composeai.cli.libDaemonDesktopDir"), e.remedy)
    assertEquals(0, fetches.get())
    assertFalse(File(tmp, "9.9.9").exists())
  }

  @Test
  fun `a provisioned sidecar is used under the switch`() {
    for (name in DaemonSidecarProvision.Sidecar.ANDROID.directories) {
      File(tmp, "1.0/$name").apply { mkdirs() }.resolve("a.jar").writeText("jar")
    }
    val dir =
      DaemonSidecarProvision.ensure(
        DaemonSidecarProvision.Sidecar.ANDROID,
        version = "1.0",
        cacheRoot = tmp,
        fetcher = { _, _ -> error("fetched") },
        log = {},
        noNetwork = true,
      )
    assertEquals(File(tmp, "1.0"), dir)
  }

  @Test
  fun `plain offline still degrades without the switch`() {
    val logged = mutableListOf<String>()
    val dir =
      DaemonSidecarProvision.ensure(
        DaemonSidecarProvision.Sidecar.DESKTOP,
        version = "9.9.9",
        cacheRoot = tmp,
        offline = true,
        fetcher = { _, _ -> error("fetched") },
        log = { logged += it },
        noNetwork = false,
      )
    assertNull(dir)
    assertTrue(logged.single().contains("offline mode"))
  }

  @Test
  fun `server distribution fetch refuses before asking for the newest release`() {
    val fetches = AtomicInteger()
    val e =
      assertFailsWith<NetworkDisabledException> {
        ServerDistributionProvision.ensure(
          requested = null,
          cacheRoot = tmp,
          offline = false,
          fetcher = ServerDistributionProvision.Fetcher(refusedFetcher(fetches)),
          log = {},
          noNetwork = true,
        )
      }
    assertRefusal(e)
    assertTrue(e.remedy.contains(ReleasedDistribution.SERVER.env), e.remedy)
    assertEquals(0, fetches.get())

    NetworkPolicy.withOverride(true) {
      assertFailsWith<NetworkDisabledException> {
        ServerDistributionProvision.latestVersion(
          fetcher = ServerDistributionProvision.Fetcher(refusedFetcher(fetches))
        )
      }
    }
    assertEquals(0, fetches.get())
  }

  @Test
  fun `skiko native fetch refuses before downloading`() {
    val fetches = AtomicInteger()
    val e =
      assertFailsWith<NetworkDisabledException> {
        SkikoNativeProvision.ensureAvailable(
          version = "0.0.1",
          platform = "linux-x64",
          configuredDir = null,
          cacheRoot = tmp,
          offline = false,
          fetcher = SkikoNativeProvision.Fetcher(refusedFetcher(fetches)),
          noNetwork = true,
        )
      }
    assertRefusal(e)
    assertTrue(e.remedy.contains("composeai.cli.skikoDir"), e.remedy)
    assertEquals(0, fetches.get())
  }

  @Test
  fun `xr composite fetch refuses rather than skipping the composites`() {
    // No binary is published for some hosts; there, nothing is fetched either way.
    if (XrCompositeProvision.currentPlatformToken() == null) return
    val fetches = AtomicInteger()
    val e =
      assertFailsWith<NetworkDisabledException> {
        XrCompositeProvision.ensureCached(
          version = "0.0.1",
          fetcher = XrCompositeProvision.Fetcher(refusedFetcher(fetches)),
          env = { if (it == "XDG_CACHE_HOME") tmp.path else null },
          log = {},
          noNetwork = true,
        )
      }
    assertRefusal(e)
    assertEquals(0, fetches.get())
  }

  @Test
  fun `robolectric needs pre-provisioned android-all under the switch`() {
    val e =
      assertFailsWith<NetworkDisabledException> {
        robolectricOfflineProperties(dependencyDir = null, noNetwork = true)
      }
    assertTrue(e.remedy.contains(ROBOLECTRIC_DEPS_DIR_ENV), e.remedy)

    val props = robolectricOfflineProperties(dependencyDir = tmp.path, noNetwork = true)
    assertEquals("true", props["robolectric.offline"])
    assertEquals(tmp.absolutePath, props["robolectric.dependency.dir"])
    assertEquals("true", props["composeai.fonts.offline"])

    assertEquals(emptyMap(), robolectricOfflineProperties(dependencyDir = null, noNetwork = false))
  }

  @Test
  fun `bundle coordinates are not fetched, and a miss refuses`() {
    val coordinate =
      BundleReader.ClasspathEntry.Maven(
        group = "com.example",
        artifact = "missing",
        version = "1.0",
        sha256 = null,
        type = "jar",
      )
    NetworkPolicy.withOverride(true) {
      val resolver =
        CoordinateResolver(
          repositoryRoots = emptyList(),
          warn = {},
          // How the CLI builds it (BundleRenderer, BundleDaemonCommand).
          networkEnabled = !NetworkPolicy.offline,
          remoteRepositories = listOf("$unroutable/maven2"),
          downloadCacheDir = tmp,
        )
      val e =
        assertFailsWith<NetworkDisabledException> {
          resolveBundleCoordinates(resolver, listOf(coordinate))
        }
      assertTrue(e.message!!.contains("com.example:missing:1.0"), e.message)
      assertTrue(e.remedy.contains("--embed-deps"), e.remedy)
    }
  }

  // -- remote sources and uploads
  // ------------------------------------------------------------------

  @Test
  fun `remote bundle download refuses, local file urls do not`() {
    NetworkPolicy.withOverride(true) {
      val e =
        assertFailsWith<NetworkDisabledException> {
          BundleSource.resolveToFile("$unroutable/bundle.png")
        }
      assertRefusal(e)
      val local = File(tmp, "b.png").apply { writeText("x") }
      assertEquals(local, BundleSource.resolveToFile(local.toURI().toString()))
    }
  }

  @Test
  fun `only loopback literals count, never a name that looks like one`() {
    assertTrue(NetworkPolicy.isLoopback("http://127.1.2.3:8"))
    assertFalse(NetworkPolicy.isLoopback("https://127.attacker.example/x"))
    assertFalse(NetworkPolicy.isLoopback("https://127.0.0.1.example.com"))
    assertFalse(NetworkPolicy.isLoopback("http://0127.0.0.1"))
    assertFalse(NetworkPolicy.isLoopback("http://128.0.0.1"))
    assertFalse(NetworkPolicy.isLoopback("http://[2001:db8::1]"))
  }

  @Test
  fun `a loopback bundle url that redirects off the machine is not followed`() {
    java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
      val answered = Thread {
        server.accept().use { socket ->
          socket.getInputStream().bufferedReader().let { reader ->
            while (reader.readLine()?.isNotEmpty() == true) {}
          }
          socket
            .getOutputStream()
            .write(
              ("HTTP/1.1 302 Found\r\nLocation: $unroutable/bundle.png\r\n" +
                  "Content-Length: 0\r\nConnection: close\r\n\r\n")
                .toByteArray()
            )
        }
      }
      answered.start()
      NetworkPolicy.withOverride(true) {
        val e =
          assertFailsWith<IllegalArgumentException> {
            BundleSource.resolveToFile("http://127.0.0.1:${server.localPort}/bundle.png")
          }
        assertTrue(e.message!!.contains("302"), e.message)
      }
      answered.join(5_000)
    }
    val client = OkHttpClient()
    assertFalse(NetworkPolicy.noRedirects(client, disabled = true).followRedirects)
    assertTrue(NetworkPolicy.noRedirects(client, disabled = false).followRedirects)
  }

  @Test
  fun `the switch keeps fonts offline over an explicit false`() {
    val previous = System.getProperty(NetworkPolicy.FONTS_OFFLINE_PROPERTY)
    System.setProperty(NetworkPolicy.FONTS_OFFLINE_PROPERTY, "false")
    try {
      val props =
        NetworkPolicy.withOverride(true) { BundleDaemonCommand(emptyList()).desktopFontSysProps() }
      assertTrue("-D${NetworkPolicy.FONTS_OFFLINE_PROPERTY}=true" in props, props.toString())
    } finally {
      if (previous == null) System.clearProperty(NetworkPolicy.FONTS_OFFLINE_PROPERTY)
      else System.setProperty(NetworkPolicy.FONTS_OFFLINE_PROPERTY, previous)
    }
  }

  @Test
  fun `agent access refuses a remote host and allows loopback`() {
    NetworkPolicy.withOverride(true) {
      assertRefusal(assertFailsWith<NetworkDisabledException> { AgentAccessClient(unroutable) })
      assertNotNull(AgentAccessClient("http://localhost:8723"))
    }
  }

  @Test
  fun `serve image upload refuses before the request is sent`() {
    val calls = AtomicInteger()
    val client =
      OkHttpClient.Builder()
        .addInterceptor { chain ->
          calls.incrementAndGet()
          chain.proceed(chain.request())
        }
        .build()
    val image = File(tmp, "shot.png").apply { writeText("png") }
    NetworkPolicy.withOverride(true) {
      assertRefusal(
        assertFailsWith<NetworkDisabledException> {
          ServeImageUploader(unroutable, token = "t", client = client).upload(image)
        }
      )
    }
    assertEquals(0, calls.get())
  }

  @Test
  fun `share-preview refuses before choosing a mechanism`() {
    val report = File(tmp, "report.md").apply { writeText("# r") }
    NetworkPolicy.withOverride(true) {
      assertRefusal(
        assertFailsWith<NetworkDisabledException> {
          SharePreviewCommand(listOf("--mechanism", "gist", report.path)).run()
        }
      )
    }
  }

  @Test
  fun `update refuses before running the installer`() {
    NetworkPolicy.withOverride(true) {
      assertRefusal(assertFailsWith<NetworkDisabledException> { UpdateCommand(emptyList()).run() })
    }
  }

  // -- guidelines
  // ----------------------------------------------------------------------------------

  @Test
  fun `guidelines refuse a remote model backend and keep local ones`() {
    assertFailsWith<NetworkDisabledException> {
      refuseRemoteGuidelinesBackend(GuidelinesBackend.OPENROUTER, null, disabled = true)
    }
    assertFailsWith<NetworkDisabledException> {
      refuseRemoteGuidelinesBackend(
        GuidelinesBackend.OPENAI,
        "https://api.openai.com/v1",
        disabled = true,
      )
    }
    refuseRemoteGuidelinesBackend(
      GuidelinesBackend.OPENAI,
      "http://localhost:11434/v1",
      disabled = true,
    )
    refuseRemoteGuidelinesBackend(GuidelinesBackend.PROMPT, null, disabled = true)
    refuseRemoteGuidelinesBackend(GuidelinesBackend.OPENROUTER, null, disabled = false)
  }

  @Test
  fun `guidelines models listing refuses before fetching`() {
    var fetched = false
    NetworkPolicy.withOverride(true) {
      assertFailsWith<NetworkDisabledException> {
        GuidelinesModelsCommand(
            emptyList(),
            fetch = {
              fetched = true
              emptyList()
            },
          )
          .list()
      }
    }
    assertFalse(fetched)
  }

  @Test
  fun `guidelines refuse a url and a file's remote includes, and read a flat file`() {
    assertFailsWith<NetworkDisabledException> {
      loadGuidelines("$unroutable/ui-builder.guidelines.json", disabled = true)
    }
    val rules =
      """
      "rules": [{"id": "a.b", "kind": "visual", "severity": "warning", "guidance": "g",
                 "check": "ok?", "source": "https://developer.android.com/x"}]
      """
    val flat =
      File(tmp, "flat.json").apply {
        writeText(
          """{"schema": "compose-ui-builder/catalog-guidelines/v1", "catalog": "c", "platform": "wear", "version": 1, $rules}"""
        )
      }
    val loaded = loadGuidelines(flat.path, disabled = true)
    assertNotNull(loaded.guidelines, loaded.problem)
    val including =
      File(tmp, "including.json").apply {
        writeText(
          """{"schema": "compose-ui-builder/catalog-guidelines/v1", "catalog": "c", "platform": "wear", "version": 1, $rules,
             "includes": [{"url": "$unroutable/pack.json", "sha256": "${"0".repeat(64)}"}]}"""
        )
      }
    val e =
      assertFailsWith<NetworkDisabledException> { loadGuidelines(including.path, disabled = true) }
    assertTrue(e.message!!.contains("pack.json"), e.message)
  }

  // -- doctor
  // --------------------------------------------------------------------------------------

  @Test
  fun `doctor reports the switch and what is pre-provisioned`() {
    assertNull(networkPolicyCheck(disabled = false, offline = false, provisioned = emptyMap()))
    val check =
      assertNotNull(
        networkPolicyCheck(
          disabled = true,
          offline = true,
          provisioned = mapOf("desktop render daemon" to true, "preview server" to false),
        )
      )
    assertEquals("env.network-policy", check.id)
    assertEquals("warning", check.status)
    assertTrue(check.message.contains(NetworkPolicy.ENV))
    assertTrue(check.detail!!.contains("missing: preview server"))
    val offlineOnly =
      assertNotNull(
        networkPolicyCheck(disabled = false, offline = true, provisioned = mapOf("x" to false))
      )
    assertEquals("ok", offlineOnly.status)
  }
}
