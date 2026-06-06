package ee.schimke.composeai.cli

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticsDiffCommandTest {

  private val tmp = Files.createTempDirectory("semantics-diff").toFile()

  @AfterTest
  fun cleanup() {
    tmp.deleteRecursively()
  }

  private fun writeSemantics(name: String, childText: String): String {
    val file = tmp.resolve(name)
    file.writeText(
      """
      {"root":{"nodeId":"1","boundsInRoot":"0,0,64,64","children":[
        {"nodeId":"2","boundsInRoot":"0,0,20,20","testTag":"label","text":"$childText"}
      ]}}
      """
        .trimIndent()
    )
    return file.absolutePath
  }

  @Test
  fun textChangeIsReportedAsAFieldChangeOnTheSameRef() {
    val base = writeSemantics("base.json", "Hello")
    val head = writeSemantics("head.json", "Goodbye")

    val outcome = computeSemanticsDiff(base, head)
    assertTrue(outcome is SemanticsDiffOutcome.Ok, "expected Ok, got $outcome")
    val delta = outcome.delta

    assertTrue(delta.added.isEmpty())
    assertTrue(delta.removed.isEmpty())
    assertEquals(1, delta.changed.size)
    val change = delta.changed.single()
    assertEquals("r/tag:label", change.ref)
    assertEquals("text", change.changes.single().field)
    assertEquals("Hello", change.changes.single().from)
    assertEquals("Goodbye", change.changes.single().to)
  }

  @Test
  fun identicalTreesProduceAnEmptyDelta() {
    val base = writeSemantics("a.json", "Same")
    val head = writeSemantics("b.json", "Same")
    val outcome = computeSemanticsDiff(base, head) as SemanticsDiffOutcome.Ok
    assertTrue(outcome.delta.isEmpty)
    assertEquals("No semantic changes.", formatSemanticsDeltaHuman(outcome.delta))
  }

  @Test
  fun humanOutputNamesTheChangedFieldAndAnchor() {
    val base = writeSemantics("base.json", "Hello")
    val head = writeSemantics("head.json", "Goodbye")
    val human =
      formatSemanticsDeltaHuman((computeSemanticsDiff(base, head) as SemanticsDiffOutcome.Ok).delta)
    assertTrue(human.contains("1 changed"), human)
    assertTrue(human.contains("tag:label"), human)
    assertTrue(human.contains("text: Hello → Goodbye"), human)
  }

  @Test
  fun collectOperandsSkipsValuedGlobalOptionValues() {
    // `compose-preview --module :app diff-semantics base.json head.json --json` — Main strips the
    // command token, so the command sees the global option + its value ahead of the two paths.
    assertEquals(
      listOf("base.json", "head.json"),
      collectOperands(listOf("--module", ":app", "base.json", "head.json", "--json")),
    )
    // `--flag=value` form consumes one token; own boolean flags consume one; operands survive.
    assertEquals(
      listOf("a", "b"),
      collectOperands(listOf("--variant=demoDebug", "a", "--fail-on-change", "b")),
    )
  }

  @Test
  fun missingFileIsAnError() {
    val base = writeSemantics("base.json", "Hello")
    val outcome = computeSemanticsDiff(base, tmp.resolve("does-not-exist.json").absolutePath)
    assertTrue(outcome is SemanticsDiffOutcome.Error)
    assertEquals(1, outcome.code)
  }

  @Test
  fun directoryArgResolvesTheCanonicalSemanticsFile() {
    // The daemon writes build/compose-previews/data/<id>/compose-semantics.json — point at the dir.
    val baseDir = tmp.resolve("base-id").also { it.mkdirs() }
    baseDir
      .resolve("compose-semantics.json")
      .writeText("""{"root":{"nodeId":"1","boundsInRoot":"0,0,10,10","testTag":"x","text":"A"}}""")
    val headDir = tmp.resolve("head-id").also { it.mkdirs() }
    headDir
      .resolve("compose-semantics.json")
      .writeText("""{"root":{"nodeId":"1","boundsInRoot":"0,0,10,10","testTag":"x","text":"B"}}""")

    val outcome = computeSemanticsDiff(baseDir.absolutePath, headDir.absolutePath)
    assertTrue(outcome is SemanticsDiffOutcome.Ok)
    val change = outcome.delta.changed.single()
    assertEquals("text", change.changes.single().field)
  }
}
