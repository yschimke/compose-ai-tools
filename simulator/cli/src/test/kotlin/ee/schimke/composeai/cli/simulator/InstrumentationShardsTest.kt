package ee.schimke.composeai.cli.simulator

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class InstrumentationShardsTest {

  @Test
  fun `merges the shards' reports into one`() {
    val dir = Files.createTempDirectory("shards").toFile()
    InstrumentationShards.shardJson(dir, 0)
      .writeText(
        """{"tests":2,"passed":1,"failed":1,"skipped":0,"durationMs":5,"cases":[""" +
          """{"class":"a.A","method":"one","status":"passed","durationMs":1},""" +
          """{"class":"a.A","method":"two","status":"failed","durationMs":2,"message":"no <",""" +
          """"trace":"at a.A"}]}""",
      )
    InstrumentationShards.shardJson(dir, 1)
      .writeText(
        """{"tests":1,"passed":0,"failed":0,"skipped":1,"durationMs":3,"cases":[""" +
          """{"class":"b.B","method":"three","status":"skipped","durationMs":0}]}""",
      )
    val reports = (0..1).map { InstrumentationShards.shardJson(dir, it) }
    val merged = InstrumentationShards.merge(reports, listOf(1, 0), 42)

    assertEquals(3, merged["tests"]!!.jsonPrimitive.int)
    assertEquals(1, merged["failed"]!!.jsonPrimitive.int)
    assertEquals(1, merged["skipped"]!!.jsonPrimitive.int)
    assertEquals(42, merged["durationMs"]!!.jsonPrimitive.int)
    assertEquals(3, merged["cases"]!!.jsonArray.size)
    assertFalse(InstrumentationShards.passed(merged, listOf(1, 0)))
    val xml = InstrumentationShards.xml(merged)
    assertTrue("""<failure message="no &lt;">at a.A</failure>""" in xml, xml)
    assertTrue("<skipped/>" in xml, xml)
    assertTrue("""tests="3" failures="1" skipped="1"""" in xml, xml)
  }

  @Test
  fun `a shard that wrote nothing fails the run`() {
    val dir = Files.createTempDirectory("shards").toFile()
    InstrumentationShards.shardJson(dir, 0)
      .writeText("""{"tests":1,"passed":1,"failed":0,"skipped":0,"cases":[]}""")
    val reports = (0..1).map { InstrumentationShards.shardJson(dir, it) }
    val merged = InstrumentationShards.merge(reports, listOf(0, 134), 1)

    assertEquals(
      "shard 2/2 exited with 134 and wrote no results",
      merged["error"]!!.jsonPrimitive.content,
    )
    assertFalse(InstrumentationShards.passed(merged, listOf(0, 134)))
    assertTrue(
      InstrumentationShards.summary(merged, listOf(0, 134)).startsWith("INSTRUMENTATION_FAILED"),
    )
  }

  @Test
  fun `each shard gets its own report and its share`() {
    val dir = Files.createTempDirectory("shards").toFile()
    val base =
      mapOf(
        "composeai.simulator.instrument" to "t.jar",
        InstrumentationShards.JSON_PROPERTY to "/out/results.json",
        InstrumentationShards.REPORT_PROPERTY to "/out/TEST.xml",
      )
    val properties = InstrumentationShards.properties(base, 2, 3, dir)

    assertEquals("2/3", properties[InstrumentationShards.SHARD_PROPERTY])
    assertEquals(
      InstrumentationShards.shardJson(dir, 2).absolutePath,
      properties[InstrumentationShards.JSON_PROPERTY],
    )
    assertFalse(InstrumentationShards.REPORT_PROPERTY in properties)
    assertEquals("t.jar", properties["composeai.simulator.instrument"])
  }
}
