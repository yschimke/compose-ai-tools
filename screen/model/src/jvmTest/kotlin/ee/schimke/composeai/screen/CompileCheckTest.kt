package ee.schimke.composeai.screen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the client half of the playground compile contract.
 *
 * The bodies below are the server's real wire shapes (`PlaygroundRunResponse`,
 * `PlaygroundCatalogsResponse` in `compose-preview-server`), not invented ones — the point of these
 * tests is that this client agrees with a contract it does not own.
 */
class CompileCheckTest {

  private val m3 =
    CompileCatalogInfo(
      id = "compose-m3",
      label = "Material 3",
      backend = "desktop",
      modes = listOf("compose-cmp"),
      resolved = true,
      system = "compose-m3",
    )

  @Test
  fun `the target is the M3 catalog, because that is what carries the classpath`() {
    val target = CompileCheck.targetFor(listOf(m3))
    assertEquals(CompileTarget("compose-m3", "compose-cmp", "Material 3"), target)
  }

  @Test
  fun `a host with no M3 catalog gets no target, rather than someone else's design system`() {
    val wear =
      CompileCatalogInfo(
        id = "wear-m3",
        label = "Wear",
        modes = listOf("compose-android"),
        system = "wear-m3",
      )
    assertNull(
      "compiling M3 source against a Wear classpath would report the screen as broken",
      CompileCheck.targetFor(listOf(wear)),
    )
    assertNull("an empty host offers nothing", CompileCheck.targetFor(emptyList()))
  }

  @Test
  fun `a catalog advertising no mode cannot be compiled against`() {
    assertNull(CompileCheck.targetFor(listOf(m3.copy(modes = emptyList()))))
  }

  @Test
  fun `desktop CMP is preferred when the catalog offers more than one renderer`() {
    val both = m3.copy(modes = listOf("compose-android", "compose-cmp"))
    assertEquals("compose-cmp", CompileCheck.targetFor(listOf(both))?.confType)
  }

  @Test
  fun `a host that omits the system field means it equals the id`() {
    val terse = CompileCatalogInfo(id = "compose-m3", label = "M3", modes = listOf("compose-cmp"))
    assertEquals("compose-m3", CompileCheck.targetFor(listOf(terse))?.catalog)
  }

  @Test
  fun `catalogs parse from the server's own response shape, unknown fields and all`() {
    val body =
      """
      {"catalogs":[
        {"id":"compose-m3","label":"Material 3","backend":"desktop","modes":["compose-cmp"],
         "resolved":true,"system":"compose-m3","module":"","somethingAddedLater":42}
      ]}
      """
        .trimIndent()
    val catalogs = CompileCheck.parseCatalogs(body)
    assertEquals(1, catalogs.size)
    assertEquals("compose-m3", catalogs.single().servedSystem)
  }

  @Test
  fun `the request posts the source verbatim under the contract's field names`() {
    val source = "import androidx.compose.material3.Button\n\n@Composable\nfun S() {\n}\n"
    val body =
      CompileCheck.requestBody(source, CompileTarget("compose-m3", "compose-cmp", "Material 3"))
    val obj = Json.parseToJsonElement(body).jsonObject
    assertEquals("compose-cmp", obj["confType"]!!.jsonPrimitive.content)
    assertEquals("compose-m3", obj["catalog"]!!.jsonPrimitive.content)
    val file = obj["files"]!!.jsonArrayFirst()
    assertEquals("Screen.kt", file["name"]!!.jsonPrimitive.content)
    assertEquals(
      "a reformat between generating and posting would shift every diagnostic's line",
      source,
      file["text"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun `a clean compile carries the frame and the token, not just a pass`() {
    val body =
      """
      {"diagnostics":[],"errors":{},"text":"","image":"data:image/png;base64,AAA",
       "previewToken":"tok-1","previewUrl":"/p/tok-1","incremental":false}
      """
        .trimIndent()
    val outcome = CompileCheck.readResponse(body) as CompileOutcome.Checked
    assertTrue(outcome.compiles)
    assertEquals("data:image/png;base64,AAA", outcome.image)
    assertEquals("tok-1", outcome.previewToken)
    assertEquals("/p/tok-1", outcome.previewUrl)
  }

  @Test
  fun `an error is a successful check that reports where it is`() {
    val body =
      """
      {"diagnostics":[
        {"severity":"error","message":"unresolved reference: Buton","file":"Screen.kt","line":12,"ch":4,
         "endLine":12,"endCh":9},
        {"severity":"warning","message":"never used","file":"Screen.kt","line":3,"ch":0}
      ]}
      """
        .trimIndent()
    val outcome = CompileCheck.readResponse(body) as CompileOutcome.Checked
    assertTrue("errors present", !outcome.compiles)
    assertEquals(1, outcome.errors.size)
    // 0-based on the wire, 1-based where a person reads it.
    assertEquals("Screen.kt:13:5", outcome.errors.single().location())
    assertNull("no frame is minted for a failed compile", outcome.image)
  }

  @Test
  fun `a file-level diagnostic has no location rather than a made-up one`() {
    val d = CompileDiagnostic(CompileSeverity.ERROR, "backend failure")
    assertNull(d.location())
  }

  @Test
  fun `a host-side exception is not blamed on the screen`() {
    val outcome = CompileCheck.readResponse("""{"exception":"render subprocess died"}""")
    assertEquals(CompileOutcome.Failed("render subprocess died"), outcome)
  }

  @Test
  fun `a reply that is not a compile result fails rather than throwing into the composition`() {
    val outcome = CompileCheck.readResponse("<html>502 Bad Gateway</html>")
    assertTrue(outcome is CompileOutcome.Failed)
    assertTrue((outcome as CompileOutcome.Failed).message.startsWith("the host's reply was not"))
  }

  @Test
  fun `urls are built against a host with or without a trailing slash`() {
    assertEquals("http://h:8080/api/1/compiler/run", CompileCheck.runUrl("http://h:8080"))
    assertEquals("http://h:8080/api/1/compiler/run", CompileCheck.runUrl("http://h:8080/"))
    assertEquals(
      "http://h:8080/api/1/compiler/catalogs",
      CompileCheck.catalogsUrl("http://h:8080/"),
    )
  }

  @Test
  fun `no compileHost means the feature is off, and only http origins are accepted`() {
    assertNull("absent is off", CompileCheck.hostFrom(emptyMap()))
    assertNull("blank is off", CompileCheck.hostFrom(mapOf("compileHost" to "   ")))
    assertEquals(
      "http://localhost:8080",
      CompileCheck.hostFrom(mapOf("compileHost" to " http://localhost:8080 ")),
    )
    assertEquals(
      "https://p.example",
      CompileCheck.hostFrom(mapOf("compileHost" to "https://p.example")),
    )
    // A crafted query is attacker-controlled input to a page an operator may have embedded.
    assertNull(CompileCheck.hostFrom(mapOf("compileHost" to "javascript:alert(1)")))
    assertNull(CompileCheck.hostFrom(mapOf("compileHost" to "data:text/html,<script>")))
    assertNull(CompileCheck.hostFrom(mapOf("compileHost" to "//evil.example")))
  }

  @Test
  fun `a relative previewUrl resolves against the host, an absolute one is left alone`() {
    assertEquals("http://h:8080/p/tok", CompileCheck.absoluteUrl("http://h:8080", "/p/tok"))
    assertEquals("http://h:8080/p/tok", CompileCheck.absoluteUrl("http://h:8080/", "p/tok"))
    assertEquals(
      "https://o.example/p/tok",
      CompileCheck.absoluteUrl("http://h:8080", "https://o.example/p/tok"),
    )
  }

  @Test
  fun `only the newest request's response is wanted`() {
    val guard = StaleGuard()
    val first = guard.issue()
    val second = guard.issue()
    assertTrue("the newest is current", guard.isCurrent(second))
    assertTrue(
      "an older response landing late must not paint over a newer edit",
      !guard.isCurrent(first),
    )
    val third = guard.issue()
    assertTrue(!guard.isCurrent(second))
    assertTrue(guard.isCurrent(third))
  }
}

private fun kotlinx.serialization.json.JsonElement.jsonArrayFirst() =
  (this as kotlinx.serialization.json.JsonArray).first().jsonObject
