package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BazelBuildTest {
  private fun eventSet(
    id: String,
    files: List<File> = emptyList(),
    children: List<String> = emptyList(),
  ): String = buildJsonObject {
    put("id", buildJsonObject { put("namedSet", buildJsonObject { put("id", id) }) })
    put(
      "namedSetOfFiles",
      buildJsonObject {
        put("files", JsonArray(files.map { buildJsonObject { put("uri", it.toURI().toString()) } }))
        put("fileSets", JsonArray(children.map { buildJsonObject { put("id", it) } }))
      },
    )
  }
    .toString()

  private fun completed(
    target: String,
    set: String,
    group: String = "compose_preview_metadata",
  ): String = buildJsonObject {
    put("id", buildJsonObject { put("targetCompleted", buildJsonObject { put("label", target) }) })
    put(
      "completed",
      buildJsonObject {
        put("success", true)
        put(
          "outputGroup",
          JsonArray(
            listOf(
              buildJsonObject {
                put("name", group)
                put("fileSets", JsonArray(listOf(buildJsonObject { put("id", set) })))
              }
            )
          ),
        )
      },
    )
  }
    .toString()

  @Test
  fun `selected target group resolves forward referenced nested sets and URI spaces`() {
    val root = Files.createTempDirectory("bazel event space ").toFile()
    try {
      val file = root.resolve("metadata space.json").apply { writeText("{}") }
      val wrong = root.resolve("wrong.json").apply { writeText("{}") }
      val bep =
        listOf(
            completed("//:other", "other"),
            completed("//:app", "root"),
            eventSet("root", children = listOf("leaf")),
            eventSet("leaf", listOf(file)),
            eventSet("other", listOf(wrong)),
          )
          .joinToString("\n")
      assertEquals(file, bazelMetadataFile(bep, "//:app", "compose_preview_metadata"))
      assertFailsWith<IllegalStateException> {
        bazelMetadataFile(bep, "//:missing", "compose_preview_metadata")
      }
      assertFailsWith<IllegalStateException> { bazelMetadataFile(bep, "//:app", "unknown") }
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `ambiguous configured target and remote metadata fail rather than guessing`() {
    val root = Files.createTempDirectory("bazel-events").toFile()
    try {
      val files = listOf("debug", "release").map { root.resolve(it).apply { writeText("{}") } }
      val bep =
        listOf(
            completed("//:app", "one"),
            completed("//:app", "two"),
            eventSet("one", listOf(files[0])),
            eventSet("two", listOf(files[1])),
          )
          .joinToString("\n")
      assertFailsWith<IllegalStateException> {
        bazelMetadataFile(bep, "//:app", "compose_preview_metadata")
      }
      val remote =
        listOf(
            completed("//:app", "one"),
            eventSet("one", listOf(files[0]))
              .replace(files[0].toURI().toString(), "bytestream://remote/output"),
          )
          .joinToString("\n")
      assertTrue(
        assertFailsWith<IllegalStateException> {
            bazelMetadataFile(remote, "//:app", "compose_preview_metadata")
          }
          .message!!
          .contains("materialize")
      )
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `artifact validation requires application runtime and materialized dependencies`() {
    val root = Files.createTempDirectory("bazel-artifacts").toFile()
    try {
      root.resolve("app.jar").writeText("")
      val metadata =
        BazelArtifacts(
          1,
          "//:app",
          "bazel-out/config/bin",
          listOf("app.jar"),
          listOf("app.jar", "runtime.jar"),
        )
      assertTrue(
        assertFailsWith<IllegalArgumentException> { metadata.resolve(root) }
          .message!!
          .contains("not materialized")
      )
      root.resolve("runtime.jar").writeText("")
      assertEquals(
        listOf(root.resolve("app.jar"), root.resolve("runtime.jar")),
        metadata.resolve(root).runtimeJars,
      )
      assertFailsWith<IllegalArgumentException> { metadata.copy(schemaVersion = 2).resolve(root) }
      assertFailsWith<IllegalArgumentException> {
        metadata.copy(runtimeJars = listOf("runtime.jar")).resolve(root)
      }
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `configurable invocation requests runtime group and keeps flags as argv`() {
    val root = Files.createTempDirectory("bazel-driver").toFile()
    try {
      root.resolve("app.jar").writeText("")
      val metadata =
        root.resolve("meta.json").apply {
          writeText(
            bazelJson.encodeToString(
              BazelArtifacts(1, "//:app", "config", listOf("app.jar"), listOf("app.jar"))
            )
          )
        }
      val calls = mutableListOf<List<String>>()
      val build = BazelBuild { argv, directory, timeout ->
        calls += argv
        assertEquals(root, directory)
        assertEquals(19, timeout.toInt())
        if ("build" in argv) {
          val bep =
            File(argv.single { it.startsWith("--build_event_json_file=") }.substringAfter('='))
          bep.writeText(
            listOf(
                completed("//:app", "set", "internal_metadata"),
                eventSet("set", listOf(metadata)),
              )
              .joinToString("\n")
          )
          ""
        } else root.absolutePath + "\n"
      }
      val config =
        BazelBuildConfig(
          buildCommand = listOf("blaze-wrapper", "literal argument"),
          startupArgs = listOf("--server=internal"),
          buildArgs = listOf("--config=desktop"),
          aspect = "//tools:preview.bzl%preview",
          metadataOutputGroup = "internal_metadata",
          runtimeOutputGroup = "render_jars",
        )
      assertEquals("//:app", build.collect(root, "//:app", config, 19).metadata.target)
      assertEquals(
        listOf("blaze-wrapper", "literal argument", "--server=internal", "build"),
        calls[0].take(4),
      )
      assertTrue("--output_groups=internal_metadata,render_jars" in calls[0])
      assertEquals(
        listOf("blaze-wrapper", "literal argument", "--server=internal", "info", "execution_root"),
        calls[1],
      )
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `init is idempotent and protects internal provider adaptations`() {
    val root = Files.createTempDirectory("bazel-init").toFile()
    try {
      installBazelAspect(root, "//third_party/java:defs.bzl")
      installBazelAspect(root, "//third_party/java:defs.bzl")
      val shim = root.resolve(".compose-preview/bazel/java_info.bzl")
      assertTrue(shim.readText().contains("//third_party/java:defs.bzl"))
      assertFailsWith<IllegalArgumentException> { installBazelAspect(root) }
      assertTrue(shim.readText().contains("//third_party/java:defs.bzl"))
    } finally {
      root.deleteRecursively()
    }
  }
}
