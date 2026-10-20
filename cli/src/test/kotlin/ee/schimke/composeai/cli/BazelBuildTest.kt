package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
          projectOutputGroup = "internal_project",
        )
      assertEquals("//:app", build.collect(root, "//:app", config, 19).metadata.target)
      // The target's own jars first (#6162); the aspect reports none, so the target stays in.
      assertEquals(
        listOf("blaze-wrapper", "literal argument", "--server=internal", "build"),
        calls[0].take(4),
      )
      assertTrue("--output_groups=internal_project" in calls[0])
      assertEquals(
        listOf("blaze-wrapper", "literal argument", "--server=internal", "info", "execution_root"),
        calls[1],
      )
      assertEquals(
        listOf("blaze-wrapper", "literal argument", "--server=internal", "build"),
        calls[2].take(4),
      )
      assertTrue("--output_groups=internal_metadata,render_jars" in calls[2])
      assertTrue("--config=desktop" in calls[2])
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `target patterns expand through query, filtered to the aspect's rule kinds`() {
    val root = Files.createTempDirectory("bazel-patterns").toFile()
    try {
      val queries = mutableListOf<List<String>>()
      val build = BazelBuild { argv, _, _ ->
        queries += argv
        "//app:ui\n//app/feature:screens\n//app:ui\n"
      }
      // One plain label is taken as given: no query.
      assertEquals(
        listOf("//app:ui"),
        build.expandTargets(root, listOf("//app:ui"), BazelBuildConfig(), 9),
      )
      assertTrue(queries.isEmpty())

      assertEquals(
        listOf("//app/feature:screens", "//app:ui"),
        build.expandTargets(
          root,
          listOf("//app/...", "//lib:all", "-//app/legacy/..."),
          BazelBuildConfig(buildCommand = listOf("blaze")),
          9,
        ),
      )
      assertEquals(
        listOf(
          "blaze",
          "query",
          "(kind('(kt_jvm_library|kt_jvm_binary|java_library|java_binary)', " +
            "\"//app/...\" + \"//lib:all\")) - \"//app/legacy/...\"",
          "--output=label",
        ),
        queries.single(),
      )

      // An Android aspect reads Android libraries; targetKinds overrides either default.
      build.expandTargets(
        root,
        listOf("//..."),
        BazelBuildConfig(
          aspect = "@rules_compose_preview//:android.bzl%compose_preview_android_aspect"
        ),
        9,
      )
      assertTrue(
        queries
          .last()
          .single { it.startsWith("(kind(") }
          .startsWith("(kind('(kt_android_library|android_library)'")
      )
      build.expandTargets(root, listOf("//..."), BazelBuildConfig(targetKinds = "my_rule"), 9)
      assertTrue(queries.last().single { it.startsWith("(kind(") }.startsWith("(kind('my_rule'"))

      // Explicit labels are requirements: never kind-filtered, and alone they need no query.
      val asked = queries.size
      assertEquals(
        listOf("//app:alias", "//app:custom"),
        build.expandTargets(root, listOf("//app:alias", "//app:custom"), BazelBuildConfig(), 9),
      )
      assertEquals(asked, queries.size)
      build.expandTargets(
        root,
        listOf("//lib/...", "//app:alias", "-//lib/old/..."),
        BazelBuildConfig(),
        9,
      )
      assertEquals(
        "(kind('(kt_jvm_library|kt_jvm_binary|java_library|java_binary)', \"//lib/...\") + " +
          "\"//app:alias\") - \"//lib/old/...\"",
        queries.last()[2],
      )

      val failure =
        assertFailsWith<IllegalArgumentException> {
          build.expandTargets(root, listOf("-//app/..."), BazelBuildConfig(), 9)
        }
      assertTrue(failure.message!!.contains("include nothing"))
      assertFailsWith<IllegalStateException> {
        BazelBuild { _, _, _ -> "" }
          .expandTargets(root, listOf("//none/..."), BazelBuildConfig(), 9)
      }
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `an unconfigured selection splits JVM and Android targets, dropping what Android exports`() {
    val root = Files.createTempDirectory("bazel-split").toFile()
    try {
      val queries = mutableListOf<String>()
      // kt_android_library: //app:lib (android_library) exports //app:lib_kt and //app:lib_base.
      // //app:ui is an alias for //app:lib, and //desktop:ui an alias for a JVM library.
      val build = BazelBuild { argv, _, _ ->
        val expression = argv[argv.indexOf("query") + 1]
        queries += expression
        when {
          expression.startsWith("kind('^alias rule\$'") -> "//app:ui\n//desktop:ui\n"
          expression.contains("labels(actual, \"//app:ui\")") -> "//app:lib\n"
          expression.contains("labels(actual, ") -> ""
          expression.startsWith("kind(") -> "//app:lib\n//app:lib_base\n"
          else -> "//app:lib_kt\n//app:lib_base\n"
        }
      }
      val (jvm, android) =
        build.splitAndroid(
          root,
          listOf("//app:lib", "//app:lib_base", "//app:lib_kt", "//app:ui", "//desktop:ui"),
          BazelBuildConfig(),
          9,
        )
      assertEquals(listOf("//desktop:ui"), jvm)
      assertEquals(listOf("//app:lib", "//app:ui"), android)
      assertTrue(
        queries.contains(
          "kind('(kt_android_library|android_library)', set(\"//app:lib\" \"//app:lib_base\" " +
            "\"//app:lib_kt\" \"//app:ui\" \"//desktop:ui\"))"
        )
      )
      assertTrue(
        queries
          .last()
          .endsWith("intersect labels(exports, set(\"//app:lib\" \"//app:lib_base\" \"//app:ui\"))")
      )

      // No Android target: the JVM selection is unchanged.
      val jvmOnly = BazelBuild { _, _, _ -> "" }
      assertEquals(
        listOf("//a:b", "//c:d") to emptyList<String>(),
        jvmOnly.splitAndroid(root, listOf("//a:b", "//c:d"), BazelBuildConfig(), 9),
      )
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `the Android aspect is rules_compose_preview's when the module declares it`() {
    val root = Files.createTempDirectory("bazel-android-aspect").toFile()
    try {
      // No MODULE.bazel (a Blaze workspace): no aspect it can load, so the selection stays JVM.
      assertNull(detectAndroidAspect(root))
      // The bundled copy needs both repositories its shims load, under their own names.
      root
        .resolve("MODULE.bazel")
        .writeText("bazel_dep(name = \"rules_android\", version = \"0.7.2\")\n")
      assertNull(detectAndroidAspect(root))
      root
        .resolve("MODULE.bazel")
        .appendText(
          "bazel_dep(name = \"rules_java\", version = \"9.3.0\", repo_name = \"java_rules\")\n"
        )
      assertNull(detectAndroidAspect(root))
      root
        .resolve("MODULE.bazel")
        .writeText(
          "bazel_dep(name = \"rules_android\", version = \"0.7.2\")\n" +
            "bazel_dep(name = \"rules_java\", version = \"9.3.0\")\n"
        )
      assertEquals(DEFAULT_BAZEL_ANDROID_ASPECT, detectAndroidAspect(root))
      // A WORKSPACE project defines the repositories instead.
      root.resolve("MODULE.bazel").renameTo(root.resolve("MODULE.bazel.off"))
      root
        .resolve("WORKSPACE.bazel")
        .writeText(
          "http_archive(\n    name = \"rules_android\",\n    urls = [\"https://example\"],\n)\n" +
            "local_repository(name = \"rules_java\", path = \"third_party/rules_java\")\n"
        )
      assertEquals(DEFAULT_BAZEL_ANDROID_ASPECT, detectAndroidAspect(root))
      root
        .resolve("WORKSPACE.bazel")
        .writeText("local_repository(name = \"rules_java\", path = \"x\")\n")
      assertNull(detectAndroidAspect(root))
      root.resolve("WORKSPACE.bazel").delete()
      root.resolve("MODULE.bazel.off").renameTo(root.resolve("MODULE.bazel"))
      root
        .resolve("MODULE.bazel")
        .appendText("bazel_dep(name = \"rules_compose_preview\", version = \"0.1.0-rc.14\")\n")
      assertEquals(RULES_ANDROID_ASPECT, detectAndroidAspect(root))
      // repo_name renames the repository the aspect loads from.
      root
        .resolve("MODULE.bazel")
        .writeText(
          "bazel_dep(\n    name = \"rules_compose_preview\",\n    version = \"0.1.0\",\n" +
            "    repo_name = \"preview_rules\",\n)\n"
        )
      assertEquals(
        "@preview_rules//:android.bzl%compose_preview_android_aspect",
        detectAndroidAspect(root),
      )
      // providers are read by the bundled copy's shims only, and make it loadable anywhere.
      root.resolve("MODULE.bazel").delete()
      assertEquals(
        DEFAULT_BAZEL_ANDROID_ASPECT,
        detectAndroidAspect(root, mapOf("JavaInfo" to "//tools:providers.bzl")),
      )
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `several targets build in one invocation and a candidate without metadata is skipped`() {
    val root = Files.createTempDirectory("bazel-multi").toFile()
    try {
      root.resolve("ui.jar").writeText("")
      root.resolve("model.jar").writeText("")
      fun metadata(target: String, jar: String) =
        root.resolve("$jar.json").apply {
          writeText(
            bazelJson.encodeToString(BazelArtifacts(target, "config", listOf(jar), listOf(jar)))
          )
        }
      val calls = mutableListOf<List<String>>()
      val build = BazelBuild { argv, _, _ ->
        calls += argv
        if ("build" in argv) {
          File(argv.single { it.startsWith("--build_event_json_file=") }.substringAfter('='))
            .writeText(
              listOf(
                  completed("//app:ui", "ui"),
                  eventSet("ui", listOf(metadata("//app:ui", "ui.jar"))),
                  completed("//app:model", "model"),
                  eventSet("model", listOf(metadata("//app:model", "model.jar"))),
                  // An Android aspect over a JVM-only library reports no metadata for it.
                  completed("//app:jvm_only", "empty"),
                  eventSet("empty"),
                )
                .joinToString("\n")
            )
          ""
        } else root.absolutePath + "\n"
      }
      val targets = listOf("//app:ui", "//app:model", "//app:jvm_only")

      val collected =
        build.collectAll(root, targets, BazelBuildConfig(), 9, skipWithoutMetadata = true)

      assertEquals(listOf("//app:ui", "//app:model"), collected.map { it.metadata.target })
      // One invocation for every target's own jars (#6162), one for the runtime of those kept:
      // the aspect reported no project jars here, so all of them.
      val builds = calls.filter { "build" in it }
      assertEquals(2, builds.size)
      builds.forEach { assertEquals(targets, it.takeLast(3)) }
      // Named explicitly, the same target is a requirement.
      assertFailsWith<IllegalStateException> {
        build.collectAll(root, targets, BazelBuildConfig(), 9)
      }
      assertFailsWith<IllegalArgumentException> {
        build.collectAll(root, listOf("//app/..."), BazelBuildConfig(), 9)
      }
    } finally {
      root.deleteRecursively()
    }
  }

  /** A jar holding one class file whose bytes are [classBytes]. */
  private fun jar(file: File, classBytes: String): File = file.apply {
    java.util.zip.ZipOutputStream(outputStream()).use { zip ->
      zip.putNextEntry(java.util.zip.ZipEntry("com/example/Screen.class"))
      zip.write(classBytes.toByteArray())
      zip.closeEntry()
    }
  }

  @Test
  fun `a target whose own jars reference no Preview is dropped before its runtime is built`() {
    val root = Files.createTempDirectory("bazel-project-group").toFile()
    try {
      jar(root.resolve("ui.jar"), "Landroidx/compose/ui/tooling/preview/Preview;")
      jar(root.resolve("wiring.jar"), "Lcom/example/MainActivity;")
      fun metadata(target: String, jar: String) =
        root.resolve("$jar.json").apply {
          writeText(
            bazelJson.encodeToString(BazelArtifacts(target, "config", listOf(jar), listOf(jar)))
          )
        }
      val builds = mutableListOf<List<String>>()
      val build = BazelBuild { argv, _, _ ->
        if ("build" in argv) {
          builds += argv
          val project = "--output_groups=compose_preview_project" in argv
          File(argv.single { it.startsWith("--build_event_json_file=") }.substringAfter('='))
            .writeText(
              (if (project)
                  listOf(
                    completed("//app:ui", "ui", "compose_preview_project"),
                    eventSet("ui", listOf(root.resolve("ui.jar"))),
                    completed("//app:wiring", "wiring", "compose_preview_project"),
                    eventSet("wiring", listOf(root.resolve("wiring.jar"))),
                  )
                else
                  listOf(
                    completed("//app:ui", "meta"),
                    eventSet("meta", listOf(metadata("//app:ui", "ui.jar"))),
                  ))
                .joinToString("\n")
            )
          ""
        } else root.absolutePath + "\n"
      }

      val collected =
        build.collectAll(
          root,
          listOf("//app:ui", "//app:wiring"),
          BazelBuildConfig(),
          9,
          skipWithoutMetadata = true,
        )

      assertEquals(listOf("//app:ui"), collected.map { it.metadata.target })
      assertEquals(2, builds.size)
      assertEquals(listOf("//app:ui", "//app:wiring"), builds[0].takeLast(2))
      // Only the target that can hold a preview is linked and has its runtime built.
      assertTrue(
        "--output_groups=compose_preview_metadata,compose_preview_runtime" in builds[1],
        builds[1].toString(),
      )
      assertEquals("//app:ui", builds[1].last())

      // Named alone, a target with none builds nothing more: the caller reports no previews.
      builds.clear()
      assertEquals(
        emptyList(),
        build.collectAll(root, listOf("//app:wiring"), BazelBuildConfig(), 9),
      )
      assertEquals(1, builds.size)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `an aspect without the project group, or the group switched off, keeps every target`() {
    val root = Files.createTempDirectory("bazel-project-group-absent").toFile()
    try {
      // Would be dropped if the group had reported it.
      jar(root.resolve("wiring.jar"), "Lcom/example/MainActivity;")
      val metadata =
        root.resolve("wiring.json").apply {
          writeText(
            bazelJson.encodeToString(
              BazelArtifacts("//app:wiring", "config", listOf("wiring.jar"), listOf("wiring.jar"))
            )
          )
        }
      val builds = mutableListOf<List<String>>()
      val build = BazelBuild { argv, _, _ ->
        if ("build" in argv) {
          builds += argv
          // An older aspect: the project group is unknown, so it reports nothing for it.
          File(argv.single { it.startsWith("--build_event_json_file=") }.substringAfter('='))
            .writeText(
              listOf(completed("//app:wiring", "meta"), eventSet("meta", listOf(metadata)))
                .joinToString("\n")
            )
          ""
        } else root.absolutePath + "\n"
      }
      assertEquals(
        listOf("//app:wiring"),
        build.collectAll(root, listOf("//app:wiring"), BazelBuildConfig(), 9).map {
          it.metadata.target
        },
      )
      assertEquals(2, builds.size)

      builds.clear()
      build.collectAll(
        root,
        listOf("//app:wiring"),
        BazelBuildConfig(projectOutputGroup = null),
        9,
      )
      assertEquals(1, builds.size)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `a jar is read for Preview by its class bytes, and kept when unreadable`() {
    val root = Files.createTempDirectory("bazel-preview-jars").toFile()
    try {
      val preview = jar(root.resolve("a.jar"), "Lcom/google/android/horologist/WearPreviewDevices;")
      val none = jar(root.resolve("b.jar"), "Lcom/example/Main;")
      val broken = root.resolve("c.jar").apply { writeText("not a zip") }
      assertTrue(BazelPreviewJars.mayHoldPreviews(listOf(none, preview)))
      assertTrue(!BazelPreviewJars.mayHoldPreviews(listOf(none)))
      assertTrue(BazelPreviewJars.mayHoldPreviews(listOf(none, broken)))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `outside any package the selection is everything below the directory`() {
    val root = Files.createTempDirectory("bazel-nopkg").toFile()
    try {
      val apps = root.resolve("apps").apply { mkdirs() }
      val queries = mutableListOf<String>()
      val build = BazelBuild { argv, _, _ ->
        queries += argv.single { it.startsWith("(kind(") }
        "//apps/a:ui\n//apps/b:ui\n"
      }
      assertEquals(
        BazelTargetSelection("//apps/...", listOf("//apps/a:ui", "//apps/b:ui")),
        build.selectTargets(root, apps, BazelBuildConfig(), 9),
      )
      assertTrue(queries.single().contains("\"//apps/...\""))
      build.selectTargets(root, root, BazelBuildConfig(), 9)
      assertTrue(queries.last().contains("\"//...\""))
      // Configured patterns win over the directory, and the singular target wins over both.
      build.selectTargets(root, apps, BazelBuildConfig(targets = listOf("//lib/...")), 9)
      assertTrue(queries.last().contains("\"//lib/...\""))
      assertEquals(
        BazelTargetSelection("//app:ui", listOf("//app:ui")),
        build.selectTargets(
          root,
          apps,
          BazelBuildConfig(target = "//app:ui", targets = listOf("//lib/...")),
          9,
        ),
      )
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `a selected set is one module on the union of its classpaths`() {
    val root = Files.createTempDirectory("bazel-merge").toFile()
    try {
      fun jar(name: String) = root.resolve(name).apply { writeText("") }
      fun target(label: String, own: String, deps: List<String>, config: String = "k8") =
        BazelArtifacts(label, config, listOf(own), listOf(own) + deps, listOf("$own.kt"))
          .also {
            jar(own)
            deps.forEach(::jar)
            jar("$own.kt")
          }
          .resolve(root)
      val ui = target("//app:ui", "ui.jar", listOf("compose.jar", "shared.jar"))
      val model = target("//app:model", "model.jar", listOf("shared.jar", "json.jar"))

      val merged = assertNotNull(mergeBazelArtifacts("//app/...", listOf(ui, model)))

      assertEquals("//app/...", merged.metadata.target)
      assertEquals(listOf("ui.jar", "model.jar"), merged.projectJars.map { it.name })
      assertEquals(
        listOf("ui.jar", "compose.jar", "shared.jar", "model.jar", "json.jar"),
        merged.runtimeJars.map { it.name },
      )
      assertEquals(merged.metadata.sourceFiles.size, merged.sourceFiles.size)
      assertNull(mergeBazelArtifacts("//app:ui", listOf(ui)))

      val other = target("//app:other", "other.jar", emptyList(), config = "k8-opt")
      assertNull(mergeBazelArtifacts("//app/...", listOf(ui, other)))
      assertTrue(bazelMergeBlocker(listOf(ui, other))!!.contains("configurations"))
      val android =
        ui.copy(
          android =
            ResolvedAndroidArtifacts("com.example", jar("res.apk"), jar("Manifest.xml"), null, 35)
        )
      assertNull(mergeBazelArtifacts("//app/...", listOf(android, model)))
      assertTrue(bazelMergeBlocker(listOf(android, model))!!.contains("mix Android and JVM"))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `target patterns are recognised the way bazel build reads them`() {
    listOf(
        "//...",
        "//app/...",
        "//app/...:all",
        "//app:all",
        "//app:*",
        "//app:all-targets",
        "-//x/...",
      )
      .forEach { assertTrue(isBazelTargetPattern(it), it) }
    listOf("//app:ui", "//app", "@repo//app:lib", "//app:all_screens").forEach {
      assertTrue(!isBazelTargetPattern(it), it)
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
  fun `the JVM aspect package file is BUILD, and an earlier CLI's BUILD_bazel stays beside it`() {
    val root = Files.createTempDirectory("bazel-build-file").toFile()
    try {
      val dir = root.resolve(".compose-preview/bazel")
      installBazelAspect(root)
      assertTrue(dir.resolve("BUILD").isFile)
      assertTrue(!dir.resolve("BUILD.bazel").exists())

      // What 2.67 and earlier installed: BUILD.bazel, which some Blaze deployments do not read. It
      // gains a BUILD and is never removed: a concurrent build may be reading it (#6115).
      val bundled = dir.resolve("BUILD").readText()
      dir.resolve("BUILD").renameTo(dir.resolve("BUILD.bazel"))
      migrateBazelAspectBuildFile(root)
      assertEquals(bundled, dir.resolve("BUILD").readText())
      assertEquals(bundled, dir.resolve("BUILD.bazel").readText())
      // And installing beside it is fine and leaves both untouched.
      val stamp = dir.resolve("BUILD").lastModified()
      installBazelAspect(root)
      assertTrue(dir.resolve("BUILD.bazel").isFile)
      assertEquals(stamp, dir.resolve("BUILD").lastModified())

      // A hand-edited one stays.
      dir.resolve("BUILD").delete()
      dir.resolve("BUILD.bazel").appendText("# local\n")
      migrateBazelAspectBuildFile(root)
      assertTrue(dir.resolve("BUILD.bazel").isFile)
      assertTrue(!dir.resolve("BUILD").exists())
      // init refuses rather than write a BUILD it would shadow.
      assertFailsWith<IllegalArgumentException> { installBazelAspect(root) }
      assertTrue(!dir.resolve("BUILD").exists())
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
      // The project-jar build first (#6162), then the runtime build: both on the canonical label.
      val builds = calls.filter { "build" in it }
      assertEquals(2, builds.size)
      assertTrue(builds.all { "@@canonical+//app:app" in it }, builds.toString())
    } finally {
      root.deleteRecursively()
    }
  }
}
