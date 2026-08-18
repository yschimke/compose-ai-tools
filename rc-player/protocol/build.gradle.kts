plugins {
  id("composeai.base-conventions")
  id("org.jetbrains.kotlin.multiplatform")
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

  jvm("desktop")
  iosX64()
  iosArm64()
  iosSimulatorArm64()

  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser() }

  sourceSets {
    commonMain {
      kotlin.srcDir(layout.buildDirectory.dir("generated/rcOperations/commonMain"))
      // `api`, not `implementation`: the tracing seam is the base of the player's dependency graph
      // (`trace <- protocol <- runtime <- compose <- wasm host`), and every module above opens
      // spans
      // through it. Exposing it here is what keeps that from being four separate declarations.
      dependencies { api(project(":rc-player-trace")) }
    }
    commonTest.dependencies { implementation(kotlin("test")) }
  }
}

tasks
  .matching { it.name.startsWith("compileKotlin") }
  .configureEach { dependsOn(generateRcOperationManifest) }

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin, so an unrecorded surface
// change would pass CI silently. Wire it explicitly — the gate is only worth having if it runs.
tasks.named("check") { dependsOn("checkKotlinAbi") }
