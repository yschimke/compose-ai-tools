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
  jvm("desktop")

  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser() }

  sourceSets {
    commonMain { kotlin.srcDir(layout.buildDirectory.dir("generated/rcOperations/commonMain")) }
    commonTest.dependencies { implementation(kotlin("test")) }
  }
}

tasks
  .matching { it.name.startsWith("compileKotlin") }
  .configureEach { dependsOn(generateRcOperationManifest) }
