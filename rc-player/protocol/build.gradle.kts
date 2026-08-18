plugins {
  id("composeai.base-conventions")
  id("org.jetbrains.kotlin.multiplatform")
  id("composeai.maven-publishing")
}

abstract class GenerateRcOperationManifest : org.gradle.api.DefaultTask() {
  @get:org.gradle.api.tasks.InputFile
  abstract val manifestFile: org.gradle.api.file.RegularFileProperty

  @get:org.gradle.api.tasks.OutputFile
  abstract val outputFile: org.gradle.api.file.RegularFileProperty

  @org.gradle.api.tasks.TaskAction
  fun generate() {
    val rows =
      manifestFile
        .get()
        .asFile
        .readLines()
        .filterNot { it.isBlank() || it.startsWith("#") }
        .mapIndexed { index, line ->
          val fields = line.split('|')
          require(fields.size == 4) { "Invalid RC manifest line ${index + 1}: $line" }
          fields
        }
    require(rows.map { it[0].toInt() }.distinct().size == rows.size) {
      "Duplicate opcode in rc-operations.manifest"
    }
    require(rows.map { it[1] }.distinct().size == rows.size) {
      "Duplicate constant in rc-operations.manifest"
    }
    val validStatuses = setOf("implemented", "parse_only", "unsupported", "unavailable", "reserved")
    require(rows.all { it[3] in validStatuses }) { "Unknown RC operation status" }

    fun stableName(constant: String): String =
      constant.lowercase().split('_').joinToString("") { word ->
        word.replaceFirstChar { it.uppercase() }
      }

    val target = outputFile.get().asFile
    target.parentFile.mkdirs()
    target.writeText(
      buildString {
        appendLine("package ee.schimke.composeai.rcplayer.protocol")
        appendLine()
        appendLine("/** Generated from the AndroidX alpha16 operation manifest. */")
        appendLine("public object RcOperationInventory {")
        appendLine("  public val entries: List<RcOperationInventoryEntry> = listOf(")
        rows
          .sortedBy { it[0].toInt() }
          .forEach { row ->
            appendLine(
              "    RcOperationInventoryEntry(${row[0]}, \"${row[1]}\", " +
                "\"${stableName(row[1])}\", ${row[2]}, " +
                "RcOperationStatus.${row[3].uppercase()}),"
            )
          }
        appendLine("  )")
        appendLine(
          "  public val byOpcode: Map<Int, RcOperationInventoryEntry> = " +
            "entries.associateBy { it.opcode }"
        )
        appendLine("}")
      }
    )
  }
}

val rcOperationManifest = layout.projectDirectory.file("src/main/rc-operations.manifest")
val generatedRcOperations =
  layout.buildDirectory.file(
    "generated/rcOperations/commonMain/ee/schimke/composeai/rcplayer/protocol/RcOperationManifest.kt"
  )

val generateRcOperationManifest =
  tasks.register<GenerateRcOperationManifest>("generateRcOperationManifest") {
    description = "Generate the KMP operation inventory from the checked-in AndroidX manifest."
    group = "build"
    manifestFile.set(rcOperationManifest)
    outputFile.set(generatedRcOperations)
  }

kotlin {
  // `explicitApi()` — every declaration in this module must state its visibility, and every public
  // declaration must state its return type. The player modules were already written this way by
  // convention (`public` modifiers throughout); this makes the convention a compile error rather
  // than a habit, so a `public` that should have been `internal` can't slip into the published
  // surface. See docs/API_STABILITY.md and #4062.
  explicitApi()

  // ABI dump gate. `checkKotlinAbi` (wired into `check` below) diffs the module's real public ABI
  // against the committed dumps in `api/`, so a change to the published surface shows up as a diff
  // in review rather than as a surprise after release. Kotlin's own ABI validation ships in the
  // Kotlin Gradle plugin from 2.2 (still `@ExperimentalAbiValidation` at 2.4), so this needs no
  // extra plugin on the classpath — which is why the player stack gets the gate first rather than
  // waiting for a repo-wide rollout (docs/API_STABILITY.md notes no module had one until now).
  // Both dumps are written: `<module>.api` for the JVM target and `<module>.klib.api` covering the
  // klib-based targets (iOS + wasmJs) together. Regenerate with `./gradlew updateKotlinAbi`.
  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()

  // Unnamed `jvm()`, not `jvm("desktop")`. This module is published, and the JVM artifact should
  // carry the conventional `-jvm` classifier — the decision `runtimes/slots/build.gradle.kts`
  // already records for a published KMP module. `-desktop` also misnames bytecode that Android
  // resolves perfectly well. The cost is that the source sets are `jvmMain`/`jvmTest` and the test
  // task is `:rc-player-<module>:jvmTest`; nothing in `ci.yml` named the old paths (it runs
  // `allTests`), so the rename is contained to this repo's source layout. See #4063.
  jvm()
  // No `iosX64()`. `:rc-player-compose` cannot declare one — CMP 1.11 dropped the Intel iOS
  // simulator variant (see that module's build comment) — and these three are published as one
  // stack with it. Keeping `iosX64` here would publish a stack that resolves three of its four
  // artifacts on that target and fails on the fourth, which is the worst of the options #4066
  // lists. Intel Macs are out; device and the Apple-silicon simulator are what remain.
  iosArm64()
  iosSimulatorArm64()

  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser() }

  sourceSets {
    commonMain {
      // `.builtBy(...)`, not a bare directory. The generated opcode table is read by more tasks
      // than the compilers — publishing (#4064) added the per-target sources jars, and the
      // metadata compile has its own name — and Gradle rejects any of them that reads the
      // directory without a declared producer ("uses this output of task
      // ':generateRcOperationManifest' without declaring an explicit or implicit dependency").
      // Attaching the producer to the source directory itself covers every consumer, present and
      // future, instead of a task-name pattern that has already needed widening twice. It is right
      // to be strict here: the quiet failure is a sources jar with a hole where
      // `RcOperationInventory` should be.
      kotlin.srcDir(
        files(layout.buildDirectory.dir("generated/rcOperations/commonMain"))
          .builtBy(generateRcOperationManifest)
      )
      // `api`, not `implementation`: the tracing seam is the base of the player's dependency graph
      // (`trace <- protocol <- runtime <- compose <- wasm host`), and every module above opens
      // spans
      // through it. Exposing it here is what keeps that from being four separate declarations.
      dependencies { api(project(":rc-player-trace")) }
    }
    commonTest.dependencies { implementation(kotlin("test")) }
  }
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin, so an unrecorded surface
// change would pass CI silently. Wire it explicitly — the gate is only worth having if it runs.
tasks.named("check") { dependsOn("checkKotlinAbi") }

composeAiMavenPublishing {
  coordinates(
    artifactId = "rc-player-protocol",
    displayName = "Remote Compose Player — Protocol",
    description =
      "The .rc wire codec and operation model for Remote Compose documents: reader, writer, immutable operation IR and the AndroidX operation inventory. Consumable without any Compose dependency.",
  )
}
