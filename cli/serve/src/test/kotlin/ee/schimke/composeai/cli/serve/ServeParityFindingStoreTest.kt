package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.FileSystem

/**
 * `parity/findings.json` is written by another repository and rendered into a page a reader trusts,
 * so every case here is about what a producer's mistake costs: a bad record, never the comparison.
 */
class ServeParityFindingStoreTest {
  private fun store(json: String?): ServeParityFindingStore {
    val root = Files.createTempDirectory("parity-findings").toFile().also { it.deleteOnExit() }
    if (json != null) {
      File(root, ParityFindings.DIRECTORY).mkdirs()
      File(root, "${ParityFindings.DIRECTORY}/${ParityFindings.FILE}").writeText(json)
    }
    return ServeParityFindingStore.load(root, FileSystem.SYSTEM)
  }

  @Test
  fun `no manifest yields an empty store`() {
    assertTrue(store(null).isEmpty)
  }

  @Test
  fun `malformed manifest is ignored rather than thrown`() {
    assertTrue(store("{ not json").isEmpty)
  }

  @Test
  fun `a manifest from a future schema is ignored`() {
    val json =
      """{"schema":"compose-preview-parity-findings/v99","previews":{"p":[
         {"findings":[{"kind":"a11y","severity":"warn","message":"x"}]}]}}"""
    assertTrue(store(json).isEmpty)
  }

  @Test
  fun `findings are exposed for the pair they describe`() {
    val json =
      """{"schema":"compose-preview-parity-findings/v1","previews":{"button__light":[
         {"referenceId":"design-button","status":"fail","findings":[
           {"kind":"token","severity":"error","message":"spacing.padding: 24 vs spec 12",
            "detail":{"token":"spacing.padding","expected":"12","actual":"24"},
            "anchors":[{"side":"actual","bounds":{"x":2,"y":3,"width":40,"height":12},
                        "label":"Label"}]}]},
         {"referenceId":"design-other","findings":[
           {"kind":"a11y","severity":"warn","message":"touch target is 40dp"}]}]}}"""
    val loaded = store(json)
    val sets = loaded.forComparison("button__light", "design-button")
    assertEquals(1, sets.size)
    assertEquals("fail", sets.single().status)
    val finding = sets.single().findings.single()
    assertEquals("spacing.padding: 24 vs spec 12", finding.message)
    assertEquals("24", finding.detail["actual"])
    assertEquals("Label", finding.anchors.single().label)
    assertTrue(loaded.forComparison("button__light", "nope").isEmpty())
    assertTrue(loaded.forComparison("nope", "design-button").isEmpty())
  }

  @Test
  fun `an unscoped set describes the render whichever reference it is read against`() {
    val json =
      """{"schema":"compose-preview-parity-findings/v1","previews":{"p":[
         {"findings":[{"kind":"contrast","severity":"error","message":"1.9:1 vs 4.5:1"}]},
         {"referenceId":"a","findings":[
           {"kind":"layout","severity":"warn","message":"offset (1, -12)"}]}]}}"""
    val loaded = store(json)
    assertEquals(2, loaded.forComparison("p", "a").size)
    // Only the unscoped one — the reference-scoped set describes a comparison this is not.
    assertEquals(1, loaded.forComparison("p", "b").size)
    assertEquals("contrast", loaded.forComparison("p", "b").single().findings.single().kind)
  }

  @Test
  fun `an unreadable finding costs only itself`() {
    val json =
      """{"schema":"compose-preview-parity-findings/v1","previews":{"p":[{"findings":[
         {"kind":"invented","severity":"warn","message":"a kind this build has not heard of"},
         {"kind":"token","severity":"critical","message":"a severity it has not either"},
         {"kind":"token","severity":"warn","message":"   "},
         {"kind":"token","severity":"warn","message":"kept"}]}]}}"""
    val findings = store(json).forPreview("p").single().findings
    assertEquals(listOf("kept"), findings.map { it.message })
  }

  @Test
  fun `a box with no area is dropped while its finding is kept`() {
    val json =
      """{"schema":"compose-preview-parity-findings/v1","previews":{"p":[{"findings":[
         {"kind":"layout","severity":"warn","message":"m","anchors":[
           {"side":"actual","bounds":{"x":0,"y":0,"width":0,"height":9}},
           {"side":"actual","bounds":{"x":-4,"y":0,"width":9,"height":9}},
           {"side":"sideways","bounds":{"x":0,"y":0,"width":9,"height":9}},
           {"side":"reference","bounds":{"x":1,"y":2,"width":9,"height":9}}]}]}]}}"""
    val finding = store(json).forPreview("p").single().findings.single()
    assertEquals("m", finding.message)
    assertEquals(1, finding.anchors.size)
    assertEquals("reference", finding.anchors.single().side)
  }

  @Test
  fun `a set with nothing readable left in it is dropped entirely`() {
    val json =
      """{"schema":"compose-preview-parity-findings/v1","previews":{"p":[{"findings":[
         {"kind":"invented","severity":"warn","message":"m"}]}]}}"""
    assertTrue(store(json).isEmpty)
  }

  @Test
  fun `findings are ordered worst first`() {
    val json =
      """{"schema":"compose-preview-parity-findings/v1","previews":{"p":[{"findings":[
         {"kind":"token","severity":"info","message":"i"},
         {"kind":"token","severity":"error","message":"e"},
         {"kind":"token","severity":"warn","message":"w"}]}]}}"""
    assertEquals(
      listOf("e", "w", "i"),
      store(json).forPreview("p").single().findings.map { it.message },
    )
  }

  @Test
  fun `a report link is only kept when it is an absolute https url`() {
    fun reportUrl(url: String): String? {
      val json =
        """{"schema":"compose-preview-parity-findings/v1","previews":{"p":[
           {"reportUrl":"$url","findings":[
             {"kind":"token","severity":"warn","message":"m"}]}]}}"""
      return store(json).forPreview("p").single().reportUrl
    }
    assertEquals("https://example.test/r.html", reportUrl("https://example.test/r.html"))
    // A page the reader trusts must not carry a producer's script URI into an `href`.
    assertNull(reportUrl("javascript:alert(1)"))
    assertNull(reportUrl("//evil.test/r.html"))
    assertNull(reportUrl("http://example.test/r.html"))
  }

  @Test
  fun `one unreadable record costs only itself, not the whole catalog`() {
    // The failure this guards is silent and total: decoding the document in one call makes any
    // single malformed record throw while parsing the envelope, and every valid verdict in the
    // catalog disappears with it.
    val json =
      """{"schema":"compose-preview-parity-findings/v1","previews":{
         "p":[
           {"findings":[{"kind":"token","severity":"warn"}]},
           {"findings":[{"kind":"token","severity":"warn","message":"kept"}]}],
         "q":[{"findings":[{"kind":"a11y","severity":"warn","message":"also kept",
                            "anchors":[
                              {"side":"actual","bounds":{"x":1,"width":4,"height":4}},
                              {"side":"actual","bounds":{"x":1,"y":2,"width":4,"height":4}}]}]}],
         "r":"not a list at all"}}"""
    val loaded = store(json)
    assertEquals(listOf("kept"), loaded.forPreview("p").single().findings.map { it.message })
    val q = loaded.forPreview("q").single().findings.single()
    assertEquals("also kept", q.message)
    // The anchor missing `y` is dropped; the finding and its good anchor survive.
    assertEquals(1, q.anchors.size)
    assertTrue(loaded.forPreview("r").isEmpty())
  }

  @Test
  fun `a detail value that is not a string is coerced rather than thrown away`() {
    val json =
      """{"schema":"compose-preview-parity-findings/v1","previews":{"p":[{"findings":[
         {"kind":"token","severity":"error","message":"m",
          "detail":{"expected":16,"actual":"24","unverified":false,"nested":{"a":1},
                    "missing":null}}]}]}}"""
    val detail = store(json).forPreview("p").single().findings.single().detail
    assertEquals("16", detail["expected"])
    assertEquals("24", detail["actual"])
    assertEquals("false", detail["unverified"])
    // An object is not a readout — JSON in a hover card is noise the reader cannot act on.
    assertNull(detail["nested"])
    assertNull(detail["missing"])
  }

  @Test
  fun `a supplied reference id that does not validate drops its set`() {
    // The worst available reading of a malformed scope is the one this prevents: `forComparison`
    // takes null as "applies to every reference", so nulling an invalid id would print this
    // verdict under every OTHER reference's panels — a plausible, wrong claim.
    val json =
      """{"schema":"compose-preview-parity-findings/v1","previews":{"p":[
         {"referenceId":"   ","findings":[{"kind":"token","severity":"warn","message":"bad"}]},
         {"referenceId":42,"findings":[{"kind":"token","severity":"warn","message":"also bad"}]},
         {"findings":[{"kind":"token","severity":"warn","message":"absent is unscoped"}]}]}}"""
    val sets = store(json).forComparison("p", "anything")
    assertEquals(listOf("absent is unscoped"), sets.flatMap { it.findings }.map { it.message })
  }

  @Test
  fun `a run that looked and found nothing keeps its verdict`() {
    // `{"status":"pass","findings":[]}` is the natural shape of a clean run, and dropping it made
    // the comparison indistinguishable from a catalog nobody ran a parity check against.
    val json =
      """{"schema":"compose-preview-parity-findings/v1","previews":{"p":[
         {"referenceId":"a","status":"pass","findings":[]}]}}"""
    val set = store(json).forComparison("p", "a").single()
    assertEquals("pass", set.status)
    assertTrue(set.findings.isEmpty())
    // …but a set with neither findings nor a status says nothing at all, and still goes.
    val silent =
      """{"schema":"compose-preview-parity-findings/v1","previews":{"p":[{"findings":[]}]}}"""
    assertTrue(store(silent).isEmpty)
  }

  @Test
  fun `the page budget is spent per comparison, not across references a page never shows`() {
    // Reference-scoped sets are mutually exclusive on screen. Charging them against one shared
    // allowance let the first two boards exhaust it and left the third comparison blank, for a
    // page that was never going to render the other two.
    fun finding(index: Int) =
      """{"kind":"layout","severity":"warn","message":"m$index","anchors":[
         {"side":"actual","bounds":{"x":0,"y":0,"width":4,"height":4}}]}"""
    fun set(reference: String) =
      """{"referenceId":"$reference","findings":[${(1..200).joinToString(",") { finding(it) }}]}"""
    val json =
      """{"schema":"compose-preview-parity-findings/v1","previews":{"p":[
         ${listOf("a", "b", "c").joinToString(",") { set(it) }}]}}"""
    val loaded = store(json)
    for (reference in listOf("a", "b", "c")) {
      assertEquals(
        200,
        loaded.forComparison("p", reference).single().findings.size,
        "board $reference keeps its own verdict",
      )
    }
  }

  @Test
  fun `an anchor label is clamped, not carried whole into every page`() {
    val json =
      """{"schema":"compose-preview-parity-findings/v1","previews":{"p":[{"findings":[
         {"kind":"layout","severity":"warn","message":"m","anchors":[
           {"side":"actual","bounds":{"x":0,"y":0,"width":4,"height":4},
            "label":"${"x".repeat(900)}"}]}]}]}}"""
    val label = store(json).forPreview("p").single().findings.single().anchors.single().label
    assertEquals(120, label?.length)
    assertTrue(label!!.endsWith("…"))
  }

  @Test
  fun `one comparison cannot publish more than a page can hold`() {
    // Nested ceilings multiply — twenty sets of two hundred findings is four thousand rows and a
    // browser that stops responding while their boxes are placed. The aggregate is the one that
    // describes the page.
    fun finding(index: Int) =
      """{"kind":"layout","severity":"warn","message":"m$index","anchors":[
         {"side":"actual","bounds":{"x":0,"y":0,"width":4,"height":4}},
         {"side":"reference","bounds":{"x":0,"y":0,"width":4,"height":4}}]}"""
    val set = """{"findings":[${(1..200).joinToString(",") { finding(it) }}]}"""
    val json =
      """{"schema":"compose-preview-parity-findings/v1","previews":{"p":[
         ${(1..20).joinToString(",") { set }}]}}"""
    val findings = store(json).forComparison("p", "any").flatMap { it.findings }
    assertEquals(300, findings.size)
    assertEquals(600, findings.sumOf { it.anchors.size })
  }

  @Test
  fun `a long message is clamped rather than dropped`() {
    val json =
      """{"schema":"compose-preview-parity-findings/v1","previews":{"p":[{"findings":[
         {"kind":"i18n","severity":"warn","message":"${"x".repeat(900)}"}]}]}}"""
    val message = store(json).forPreview("p").single().findings.single().message
    assertEquals(400, message.length)
    assertTrue(message.endsWith("…"))
    assertFalse(store(json).isEmpty)
  }
}
