/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ee.schimke.composeai.rcembedded

import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.Operation
import androidx.compose.remote.core.RemoteClock
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.core.VariableProvider
import androidx.compose.remote.core.operations.layout.Container
import androidx.compose.remote.core.operations.layout.LayoutComponent
import androidx.compose.remote.player.compose.embedded.buildComputedOpIndex
import androidx.compose.remote.player.compose.embedded.enableEncodedImageReferences
import androidx.compose.remote.player.compose.embedded.getOperationsReflection
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Diagnostic for the embedded player's dropped dynamic-colour shapes (compose-ai-tools#3936, defect
 * 1).
 *
 * A tinted badge whose background is a **literal** colour draws; an adjacent one whose background
 * is a **dynamic** colour id (`flags=2, colorId=…`, produced by a `ColorExpression` ->
 * `ColorAttribute` -> `ColorExpression` chain) renders as the card background instead. This prints
 * where each op in that chain actually lives in the tree and whether the computed-op index the
 * graph resolves through contains it — the fact a fix has to be built on, rather than another guess
 * about which walk is short.
 *
 * Skips unless a document is staged, so it costs nothing in a normal run:
 * ```
 * ./gradlew :third-party-rc-embedded-player:testDebugUnitTest \
 *   --tests '*DynamicColorDiagTest*' -Prc.embedded.input=<dir with the .rc files> \
 *   [-Prc.dynamic-color.report=<output file>]
 * ```
 *
 * The report defaults to this module's `build/reports/dynamic-color-diag.txt`; the staged input is
 * never mutated.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DynamicColorDiagTest {

  @Test
  fun reportDynamicColourChain() {
    val configuredDir =
      System.getProperty("rc.embedded.input")?.let(::File)?.takeIf { it.isDirectory }
    assumeTrue("no rc.embedded.input staged", configuredDir != null)
    val dir = checkNotNull(configuredDir)
    val manifest = File(dir, "manifest.json")
    assumeTrue("no manifest.json staged", manifest.isFile)
    val output = File(checkNotNull(System.getProperty("rc.dynamic-color.report")))
    output.parentFile?.mkdirs()

    val entries = Json.decodeFromString<List<RcEmbeddedRenderHarness.Entry>>(manifest.readText())

    val report = StringBuilder()
    entries
      .sortedBy { it.id }
      .forEach { entry ->
        val rc = File(dir, "${entry.id}.rc")
        require(rc.isFile) { "manifest document has no staged input: ${entry.id}" }
        enableEncodedImageReferences()
        val document =
          CoreDocument(RemoteClock.SYSTEM).apply {
            ByteArrayInputStream(rc.readBytes()).use {
              initFromBuffer(RemoteComposeBuffer.fromInputStream(it))
            }
          }
        val index = buildComputedOpIndex(document.getOperationsReflection())

        // Where every op of interest actually sits: top level, inside a container, or only
        // reachable through a component's canvas operations.
        val located = LinkedHashMap<String, MutableList<Pair<Int?, String>>>()
        fun visit(ops: Collection<Operation>, path: String) {
          for (op in ops) {
            val name = op.javaClass.simpleName
            if (name.startsWith("Color")) {
              val id = (op as? VariableProvider)?.id
              located.getOrPut(name) { mutableListOf() }.add(id to path)
            }
            if (op is Container) visit(op.getList(), "$path/container")
            if (op is LayoutComponent) {
              op.getCanvasOperations()?.let { visit(listOf(it), "$path/canvasOps") }
            }
          }
        }
        visit(document.getOperationsReflection(), "root")

        report.appendLine("=== ${entry.id}")
        report.appendLine("  computed-op index size = ${index.size}; ids = ${index.keys.sorted()}")
        located.forEach { (name, operations) ->
          report.appendLine(
            "  $name -> " + operations.joinToString { (id, path) -> "id=${id ?: "none"}@$path" }
          )
        }
        report.appendLine(
          "  indexed op types = ${index.values.map { it.javaClass.simpleName }.toSortedSet()}"
        )
      }
    // Written to a file rather than stdout: Gradle does not surface test stdout here, and the
    // point of a diagnostic is that its output survives the harness.
    output.writeText(report.toString())
  }
}
