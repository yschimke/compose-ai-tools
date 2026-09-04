package com.example.designcatalogm3.shared

import ee.schimke.composeai.screen.ComponentSpec
import ee.schimke.composeai.screen.KnobKind
import ee.schimke.composeai.screen.KnobSpec

/**
 * How each catalog id becomes real Compose source — the table `ScreenCodegen` needs and
 * deliberately does not own.
 *
 * The model knows a screen is a tree of ids; it does not know that `button-filled` is a `Button`,
 * and it should not. Keeping the mapping here, beside the component bodies it describes, is what
 * makes codegen honest about its limits: an id absent from this table cannot be generated and says
 * so, rather than emitting something that looks like Kotlin and does not compile.
 *
 * ### Why this is not every component
 *
 * A generated call has to be one a developer would have written. Several catalog ids are *stateful
 * demonstrations* rather than components — `checkbox-checked` is a `StatefulCheckbox` that exists
 * to show a state layer, `shape-morph` is a viewer, `button-filled-pressed` is the same `Button`
 * under an interaction the generated code has no way to express. Emitting those as their catalog
 * spelling would produce code referencing helpers that live only in this sample. They are omitted
 * on purpose, and a screen using one gets a `// TODO` naming it, which is the honest answer.
 */
val catalogComponentSpecs: Map<String, ComponentSpec> =
  mapOf(
    // Containers — the three `CatalogScreen` renders, mapped to what a developer would write.
    "column" to
      ComponentSpec(
        call = "Column",
        imports = listOf("androidx.compose.foundation.layout.Column"),
        container = true,
      ),
    "lazy-column" to
      ComponentSpec(
        call = "LazyColumn",
        imports = listOf("androidx.compose.foundation.lazy.LazyColumn"),
        container = true,
      ),
    "card" to
      ComponentSpec(
        call = "ElevatedCard",
        imports = listOf("androidx.compose.material3.ElevatedCard"),
        container = true,
      ),

    // Leaves. Each knob maps to the parameter the real composable takes, which is not always the
    // knob's own name: the catalog's `label` is a `Button`'s content, so it is generated as the
    // `Text` inside rather than an argument — see the `content` note below.
    "button-filled" to
      ComponentSpec(
        call = "Button",
        imports = listOf("androidx.compose.material3.Button", "androidx.compose.material3.Text"),
        knobs = mapOf("enabled" to KnobSpec("enabled", KnobKind.BOOLEAN)),
        // A button's label is its child, not a parameter — `Button(onClick = {}) { Text("Open") }`
        // is the line a developer writes, and `onClick` is required whether or not a screen has an
        // opinion about it.
        requiredArgs = listOf("onClick = {}"),
        contentKnob = "label",
      ),
    "textfield-filled" to
      ComponentSpec(
        call = "TextField",
        imports = listOf("androidx.compose.material3.TextField", "androidx.compose.material3.Text"),
        knobs = mapOf("value" to KnobSpec("value")),
        requiredArgs = listOf("onValueChange = {}"),
        contentKnob = "label",
      ),
    "progress-linear" to
      ComponentSpec(
        call = "LinearProgressIndicator",
        imports = listOf("androidx.compose.material3.LinearProgressIndicator"),
        knobs = mapOf("progress" to KnobSpec("progress", KnobKind.FLOAT)),
      ),
    "slider" to
      ComponentSpec(
        call = "Slider",
        imports = listOf("androidx.compose.material3.Slider"),
        knobs = mapOf("value" to KnobSpec("value", KnobKind.FLOAT)),
      ),
    "badge" to
      ComponentSpec(
        call = "Badge",
        imports = listOf("androidx.compose.material3.Badge", "androidx.compose.material3.Text"),
        contentKnob = "count",
      ),
    "text-maxlines-truncated" to
      ComponentSpec(
        call = "Text",
        imports = listOf("androidx.compose.material3.Text"),
        knobs = mapOf("text" to KnobSpec("text")),
      ),
  )
