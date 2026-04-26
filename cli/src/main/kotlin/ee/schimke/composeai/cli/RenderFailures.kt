package ee.schimke.composeai.cli

import java.io.File
import java.io.PrintStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

data class RenderTestFailure(
  val module: String,
  val className: String,
  val testName: String,
  val kind: String,
  val message: String?,
  val stackTrace: String?,
)

/**
 * Walks `<module>/build/test-results/<taskName>/TEST-*.xml` for each module and parses any
 * `<failure>`/`<error>` elements out of the JUnit XML reports Gradle's `Test` task always writes.
 *
 * The HTML report Gradle's "What went wrong" message points at is unreachable from CI logs (the
 * runner's filesystem is gone by the time a human reads the log), so the CLI surfaces the same
 * information by reading the sidecar XML — it's been the targeted source of truth since JUnit 4.
 */
internal fun collectRenderTestFailures(
  modules: List<PreviewModule>,
  taskName: String = "renderPreviews",
): List<RenderTestFailure> {
  val failures = mutableListOf<RenderTestFailure>()
  for (module in modules) {
    val resultsDir = module.projectDir.resolve("build/test-results/$taskName")
    if (!resultsDir.isDirectory) continue
    val xmls =
      resultsDir.listFiles { f -> f.name.startsWith("TEST-") && f.name.endsWith(".xml") }
        ?: continue
    for (xml in xmls.sortedBy { it.name }) {
      failures += parseJUnitXml(xml, module.gradlePath)
    }
  }
  return failures
}

internal fun printRenderTestFailures(
  failures: List<RenderTestFailure>,
  err: PrintStream = System.err,
  stackLines: Int = 6,
) {
  if (failures.isEmpty()) return
  // Group by module so the agent reading the log can map each block back
  // to the Gradle subproject without scanning every line for context.
  val byModule = failures.groupBy { it.module }
  for ((module, entries) in byModule) {
    err.println()
    err.println("Failing tests in :$module:renderPreviews (${entries.size}):")
    for (failure in entries) {
      err.println("  ${failure.kind} ${failure.className}.${failure.testName}")
      val msg = failure.message?.lines()?.firstOrNull { it.isNotBlank() }?.trim()
      if (!msg.isNullOrEmpty()) err.println("    ${msg}")
      // Trim the stack trace to keep the failure block short — agents
      // can rerun with --verbose for the full thing.
      val trimmed =
        failure.stackTrace?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(stackLines)
          ?: emptyList()
      for (line in trimmed) err.println("    $line")
    }
  }
}

private fun parseJUnitXml(file: File, module: String): List<RenderTestFailure> {
  return try {
    val factory =
      DocumentBuilderFactory.newInstance().apply {
        // The XML files are produced by Gradle's own Test task on the
        // local filesystem so XXE isn't a real threat — but harden the
        // parser anyway so we don't regret it later if the input source
        // ever broadens.
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
      }
    val doc = factory.newDocumentBuilder().parse(file)
    val cases = doc.getElementsByTagName("testcase")
    val out = mutableListOf<RenderTestFailure>()
    for (i in 0 until cases.length) {
      val tc = cases.item(i) as? Element ?: continue
      val cls = tc.getAttribute("classname").orEmpty()
      val name = tc.getAttribute("name").orEmpty()
      val children = tc.childNodes
      for (j in 0 until children.length) {
        val child = children.item(j)
        if (child.nodeType != Node.ELEMENT_NODE) continue
        val el = child as Element
        if (el.nodeName != "failure" && el.nodeName != "error") continue
        out +=
          RenderTestFailure(
            module = module,
            className = cls,
            testName = name,
            kind = el.nodeName,
            message = el.getAttribute("message").takeIf { it.isNotEmpty() },
            stackTrace = el.textContent?.takeIf { it.isNotBlank() },
          )
      }
    }
    out
  } catch (e: Exception) {
    // A malformed XML report shouldn't itself become an error — fall
    // back to the existing Gradle exception chain. Surfacing the parse
    // error would just muddy the failure log.
    emptyList()
  }
}
