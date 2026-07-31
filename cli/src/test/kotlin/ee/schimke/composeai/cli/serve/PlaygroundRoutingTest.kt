package ee.schimke.composeai.cli.serve

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * The **playground lane** over real HTTP: `POST /api/{v}/compiler/run` returns the Stage-1 result
 * (diagnostics + preview token) when the lane is enabled, and 404s when it isn't. The compile
 * service is driven by fakes (a real compile is `PlaygroundBtaCompiler`'s job, covered elsewhere)
 * so this is purely about the route wiring + JSON contract.
 */
class PlaygroundRoutingTest {

  private val fs = FakeFileSystem()
  private var workN = 0

  private val playground =
    PlaygroundCompileService(
      catalogClasspath = { mode ->
        if (mode == PlaygroundMode.CMP) {
          PlaygroundCompileService.Classpath("compose-m3", listOf("/cat/app.jar".toPath()))
        } else {
          null
        }
      },
      compiler = PlaygroundCompileService.Compiler { _, _, _ -> emptyList() },
      discoverer =
        PlaygroundCompileService.PreviewDiscoverer { _, _ -> listOf("com.example.PScreen") },
      tokenStore = PlaygroundTokenStore(fileSystem = fs),
      newWorkDir = { "/work/run${++workN}".toPath() },
      fileSystem = fs,
    )

  private val registry = ServeSessionRegistry(open = { null })

  private val server: ServeHttpServer by lazy {
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = registry,
        defaultSessionId = "none",
        isPublic = true,
        playgroundService = playground,
      )
      .also { it.start() }
  }

  /** A host with no playground service — the lane must not exist there. */
  private val plainServer: ServeHttpServer by lazy {
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = ServeSessionRegistry(open = { null }),
        defaultSessionId = "none",
        isPublic = true,
      )
      .also { it.start() }
  }

  private val client = OkHttpClient()

  @AfterTest
  fun stop() {
    runCatching { server.stop() }
    runCatching { plainServer.stop() }
    runCatching { registry.close() }
  }

  private fun postRun(body: String, port: Int) =
    client
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:$port/api/1/compiler/run")
          .post(body.toRequestBody("application/json".toMediaType()))
          .build()
      )
      .execute()

  @Test
  fun `a clean compile returns a preview token over the run route`() {
    val body =
      """{"files":[{"name":"Snippet.kt","text":"@Preview @Composable fun P(){}"}],"confType":"compose-cmp"}"""
    postRun(body, server.port).use { resp ->
      assertEquals(200, resp.code)
      val json = Json.parseToJsonElement(resp.body!!.string()).jsonObject
      assertTrue(
        json["previewToken"]?.jsonPrimitive?.content?.startsWith("pg_") == true,
        "a clean compile mints a pg_ token: $json",
      )
      assertEquals(
        "/pg/${json["previewToken"]!!.jsonPrimitive.content}",
        json["previewUrl"]!!.jsonPrimitive.content,
      )
    }
  }

  @Test
  fun `the run route is absent when the playground lane isn't enabled`() {
    val body = """{"files":[{"name":"S.kt","text":"x"}],"confType":"compose-cmp"}"""
    postRun(body, plainServer.port).use { resp -> assertEquals(404, resp.code) }
  }

  private fun get(path: String, port: Int) =
    client.newCall(Request.Builder().url("http://127.0.0.1:$port$path").build()).execute()

  @Test
  fun `the editor page is served when the playground lane is enabled`() {
    get("/playground", server.port).use { resp ->
      assertEquals(200, resp.code)
      assertTrue(
        resp.header("Content-Type")?.contains("text/html") == true,
        "the editor page is served as HTML",
      )
      val html = resp.body!!.string()
      assertTrue(
        html.contains("id=\"pg-source\"") &&
          html.contains("id=\"pg-run\"") &&
          html.contains("/api/1/compiler/run"),
        "the editor page exposes the source box, Run button, and the compile route",
      )
    }
  }

  @Test
  fun `the editor page is absent when the playground lane isn't enabled`() {
    get("/playground", plainServer.port).use { resp -> assertEquals(404, resp.code) }
  }
}
