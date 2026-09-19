# State selection in generated Compose screens

`ScreenNode.selection` represents a structural `when` expression. The node's `componentId` is
empty and its branch bodies remain ordinary child slots, so traversal, editing and serialization
use the existing tree. It carries no component arguments or handlers. Wrap it in a normal layout
component to apply modifiers; selection itself creates no layout or receiver scope.

```kotlin
ScreenNode(
  componentId = "",
  selection = ScreenSelection(
    subject = ScreenValue.StateRead("page", "kotlin.Int"),
    cases = linkedMapOf("first" to ScreenValue.Whole(10), "second" to ScreenValue.Whole(20)),
    elseSlot = "fallback",
  ),
  slots = mapOf(
    "first" to listOf(firstPage),
    "second" to listOf(secondPage),
    "fallback" to listOf(unknownPage),
  ),
)
```

The generated `when (page.value)` matches `10` and `20`, preserving authored values rather than
using slot positions. With no fallback slot, an unmatched value composes nothing. Every branch
is validated, including ones inactive at the initial state. Missing or unreferenced slots,
duplicate values, case/subject type mismatches and undeclared state reads refuse generation.
Case values are scalar literals; subject expressions use the existing typed value vocabulary.
Numeric cases follow the same bounds and Float narrowing rules as component arguments.

Composition and remembered state follow ordinary Compose `when` behavior. This does not introduce
transitions or retain inactive branches. A builder projecting a Remote Compose StateLayout into
this shape is responsible for mapping its branch labels and values; the generator has no Remote
Compose dependency.

`ScreenSelectionTest` covers serialization, existing editing operations, type and branch validation,
string escaping, numeric duplicate detection, and preservation of layout modifier receivers.
`ScreenGeneratorCompileFunctionalTest` discovers actual Material 3 components, generates the screen,
compiles it, and clicks it with Compose UI tests. It verifies the first branch is replaced by the
second and an unmatched value shows the fallback. Its evidence is written to
`gradle-plugin/build/selection-evidence/`:

```shell
./gradlew :screen-model:jvmTest :screen-model:compileKotlinWasmJs
./gradlew :gradle-plugin:functionalTest --tests '*ScreenGeneratorCompileFunctionalTest'
```

The committed [generated source](../evidence/screen-selection/SelectedScreen.kt.txt),
[initial branch](../evidence/screen-selection/first.png) and
[branch after clicking](../evidence/screen-selection/second.png) come from that test.

A selected branch can be a clickable layout. `ScreenValue.ActionLambda` carries the same ordered,
validated `ScreenAction` list as a component handler, but can occupy a nested expression such as
`ChainLink("androidx.compose.foundation.clickable", named = mapOf("onClick" to callback))`.
The generated lambda writes declared state; it never accepts source text. Empty handlers,
undeclared targets, assignment type mismatches, non-Boolean toggles and composable API reads retain
the existing refusals. A direct parameter must be a non-composable zero-argument Unit callback.
The expression package allowlist still applies to the surrounding modifier call.

`ScreenActionLambdaTest` covers serialization, ordered writes and refusals. The server consumer's
`LayoutClickExportTest` and hosted MCP test pin the generated Kotlin for a real saved state-layout
design. Its `GeneratedLayoutClicksTest` compiles that exact source and clicks through both branches
and the fallback at densities 1 and 2, including the authored 24 dp padding. This is verified against
locally staged publications without waiting for a release.
