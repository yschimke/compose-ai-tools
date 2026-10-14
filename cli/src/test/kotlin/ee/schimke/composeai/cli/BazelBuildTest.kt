package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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
      // Remote-cache URIs resolve through pathPrefix + name when materialized locally.
      val executionRoot = root.resolve("execroot").apply { mkdirs() }
      val materialized =
        executionRoot.resolve("bazel-out/k8-fastbuild/bin/app.compose-preview.json").apply {
          parentFile.mkdirs()
          writeText("{}")
        }
      val bytestream =
        completed("//:app", "one") +
          "\n" +
          buildJsonObject {
            put("id", buildJsonObject { put("namedSet", buildJsonObject { put("id", "one") }) })
            put(
              "namedSetOfFiles",
              buildJsonObject {
                put(
                  "files",
                  JsonArray(
                    listOf(
                      buildJsonObject {
                        put("name", "app.compose-preview.json")
                        put("uri", "bytestream://cache.example/blobs/abc/2")
                        put(
                          "pathPrefix",
                          JsonArray(
                            listOf("bazel-out", "k8-fastbuild", "bin").map { JsonPrimitive(it) }
                          ),
                        )
                      }
                    )
                  ),
                )
              },
            )
          }
      assertEquals(
        materialized,
        bazelMetadataFile(bytestream, "//:app", "compose_preview_metadata", executionRoot),
      )
      materialized.delete()
      assertTrue(
        assertFailsWith<IllegalStateException> {
            bazelMetadataFile(bytestream, "//:app", "compose_preview_metadata", executionRoot)
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
      // Schema 2 promises an android block; an unknown schema is refused rather than guessed at.
      val encoded = bazelJson.encodeToString(metadata)
      listOf("\"schemaVersion\": 2", "\"schemaVersion\": 3").forEach { version ->
        assertFailsWith<IllegalArgumentException> {
          bazelJson
            .decodeFromString<BazelArtifacts>(encoded.replace("\"schemaVersion\": 1", version))
            .resolve(root)
        }
      }
      assertFailsWith<IllegalArgumentException> {
        metadata.newBuilder().apply { runtimeJars = listOf("runtime.jar") }.build().resolve(root)
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
              BazelArtifacts("//:app", "config", listOf("app.jar"), listOf("app.jar"))
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
  fun `default config passes no tool-specific build flags`() {
    assertEquals(emptyList(), BazelBuildConfig().buildArgs)
    assertEquals(
      BazelBuildConfig(installAspect = false),
      bazelJson.decodeFromString<BazelBuildConfig>("""{"installAspect": false}"""),
    )
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

  @Test
  fun `help values are valid and abbreviated labels match expanded events`() {
    assertTrue(!bazelHelpRequested(listOf("render", "--filter", "help")))
    assertTrue(!bazelHelpRequested(listOf("list", "--config", "help")))
    assertTrue(bazelHelpRequested(listOf("help")))
    assertTrue(bazelHelpRequested(listOf("render", "--help")))
    assertEquals("//app:app", normalizeBazelLabel("//app"))
    assertEquals("//app:app", normalizeBazelLabel("@@//app:app"))
    assertEquals(normalizeBazelLabel("@repo+//app"), normalizeBazelLabel("@@repo+//app:app"))
    val root = Files.createTempDirectory("bazel-labels").toFile()
    try {
      val metadata = root.resolve("metadata.json").apply { writeText("{}") }
      assertEquals(
        metadata,
        bazelMetadataFile(
          completed("//app:app", "set") + "\n" + eventSet("set", listOf(metadata)),
          "//app",
          "compose_preview_metadata",
        ),
      )
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `workspace sources map back to checkout while generated sources retain execution root`() {
    val root = Files.createTempDirectory("bazel-source-roots").toFile()
    try {
      val workspace = root.resolve("checkout").apply { mkdirs() }
      val execution = root.resolve("execution").apply { mkdirs() }
      listOf(workspace, execution).forEach { dir ->
        dir.resolve("app/Greeting.kt").apply {
          parentFile.mkdirs()
          writeText("source")
        }
      }
      execution.resolve("app.jar").writeText("")
      execution.resolve("bazel-out/config/Generated.kt").apply {
        parentFile.mkdirs()
        writeText("generated")
      }
      val artifacts =
        BazelArtifacts(
          "//:app",
          "config",
          listOf("app.jar"),
          listOf("app.jar"),
          listOf("app/Greeting.kt", "bazel-out/config/Generated.kt"),
        )
      assertEquals(
        listOf(
          workspace.resolve("app/Greeting.kt"),
          execution.resolve("bazel-out/config/Generated.kt"),
        ),
        artifacts.resolve(execution, workspace).sourceFiles,
      )
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `apparent external repositories are resolved before building and matching metadata`() {
    val root = Files.createTempDirectory("bazel-external-label").toFile()
    try {
      root.resolve("app.jar").writeText("")
      val metadata =
        root.resolve("meta.json").apply {
          writeText(
            bazelJson.encodeToString(
              BazelArtifacts(
                "@@canonical+//app:app",
                "config",
                listOf("app.jar"),
                listOf("app.jar"),
              )
            )
          )
        }
      val calls = mutableListOf<List<String>>()
      val build = BazelBuild { argv, _, _ ->
        calls += argv
        when {
          "cquery" in argv -> "@@canonical+//app:app\n"
          "build" in argv -> {
            File(argv.single { it.startsWith("--build_event_json_file=") }.substringAfter('='))
              .writeText(
                completed("@canonical+//app:app", "set") + "\n" + eventSet("set", listOf(metadata))
              )
            ""
          }
          else -> root.path
        }
      }
      assertEquals(
        "@@canonical+//app:app",
        build.collect(root, "@alias//app", BazelBuildConfig(), 10).metadata.target,
      )
      assertTrue("@@canonical+//app:app" in calls.single { "build" in it })
    } finally {
      root.deleteRecursively()
    }
  }
}
