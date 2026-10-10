# Typed UI Builder catalog adapters

Status: initial opt-in authoring API. The Compose renderer bridge is a prototype in
compose-ui-builder's `experiments/typed-catalog`, enabled only for a local tools composite build.

An application or SDK can explicitly select the components it shares, declare its editable
properties using real Kotlin property references, and generate the existing `components.json` /
`ui-builder.json` pair. Discovery remains metadata-only: it never evaluates the application's DSL
or loads its classes. An application-owned export entry point evaluates the declarations separately.

The API lives in `ee.schimke.composeai.discovery`, in the shared generator source compiled into
`screen-model` (JVM/Wasm) and `preview-discovery` (JVM). It introduces no wire format or Compose
dependency. JVM export applications use `preview-discovery`; renderer applications use `screen-model`.

## One definition

```kotlin
data class ButtonProps(val label: String, val onClick: () -> Unit)

class ButtonAdapter(record: ComponentRecord) :
  TypedComponentAdapter<ButtonProps>("acme/button", record) {
  val label = property(ButtonProps::label, AdapterValueCodecs.String, "Button")
  val click = event(ButtonProps::onClick)
}
```

Pass the real discovered record for the callable the adapter invokes. Resolve it from the current
`components.json`; do not maintain a handwritten signature. The adapter id is an explicit stable
wire identity, while `ButtonProps::label` is a source reference that fails compilation when removed
or changed to an incompatible type. `name = "caption"` can map a model property onto a differently
named API parameter; signature validation checks the mapped name.

The renderer bridge in compose-ui-builder accepts this same definition. It decodes all declared
properties before calling the component renderer. An invalid authored value produces a diagnostic
on that node, leaving sibling components renderable; correcting the value renders the component
again. It does not catch exceptions thrown by arbitrary composable code.

The registration call is:

```kotlin
val adapters = canvasAdapterRegistry {
  register(button) {
    BrandButton(
      label = value(button.label),
      onClick = callback(button.click),
      modifier = modifier,
    )
  }
}
```

That is a normal composable call compiled against the component's real library. Renamed parameters,
changed callback types and removed methods fail compilation there. The JSON adapter id is taken from
the definition, so a second registry string cannot drift from the catalog.

`event()` validates the callback and supplies a runtime dispatch name (`onDismiss` becomes
`dismiss`). Blank names and collisions between declared events are refused. The existing catalog
wire format has no general event-capability field, so these names are not advertised to an external
editor, nor does the declaration provide general callback source-export lowering. State callbacks
are published because the existing format explicitly supports their hoisting contract. General
externally discoverable events need a coordinated protocol/editor/export change before promotion.

Callback validation checks function arity, non-nullability, the absence of composable/DSL receiver
flags, the qualified Unit return classifier and exact type tokens. Discovery's current callback
input spelling uses simple names; it cannot distinguish a domain `Boolean` from `kotlin.Boolean`
there. The compiled renderer call is the authoritative input-type check. Missing structural callback
metadata is refused; regenerate older records with current discovery.

`stateChange(Props::onCheckedChange, checked)` checks the callback payload against the property's
codec at compile time. The bridge delegates write-back and event execution to the existing SDK.
`slot("content")` creates a handle used by `Slot(adapter.content)` and by the generated slot
metadata; validation checks it names a real composable slot. Slot names are currently validated at
build/test time, not referenced as Kotlin parameters. Receiver-scoped layout logic remains ordinary
Compose in the renderer; lazy DSL slots and callback payload slots are outside this initial API.

The supplied codecs are String, Boolean and Int. A codec is invariant in its value type, converts
both directions, and identifies the exact Kotlin classifier it accepts. Defaults must round trip.
Malformed supplied values are errors rather than silently replaced defaults. String and Boolean
properties advertise state binding by default; `bindable = false` removes that alternative. Int
properties are literal-only: the existing state schema supports `number` but cannot constrain it to
an Int. Explicitly enabling Int binding or attaching a state callback to it is refused at declaration
rather than accepting fractional state that later fails decoding.

## Generate and publish

```kotlin
val pair = TypedAdapterCatalog.generate(
  discovered = discoveredRecord,
  cover = UiBuilderCatalogs.CoverSheet("acme", "Acme SDK"),
  policy = UiBuilderPolicyFile.Builder(UI_BUILDER_POLICY_SCHEMA, "mobile").build(),
  adapters = listOf(button),
)
pair.writeTo(outputDirectory) // JVM preview-discovery extension; directory must not exist
```

`generate` calls the existing catalog generator after selecting only the declared components.
It refuses missing/stale records, unknown signatures, mismatched property classifiers, unadapted
required parameters, invalid callbacks/slots, duplicate component identities and generator
diagnostics. It does not silently combine another component inventory with the typed one.
Registration and generation freeze declarations so late additions cannot split the two outputs.
Policy `builtins` remain available for structural components such as a screen root. Policy
`components` must be empty; typed declarations own the discovered-component inventory. The existing
generator still rejects builtin IDs that collide with typed component IDs.

The exporter writes both standard artifacts into a staging directory and moves that complete
directory into place. Use a task-owned generation directory, deleting only that task's previous
output before a rebuild. The writer refuses to overwrite an existing destination.

Publish the pair from the same generation, beside the catalog cover sheet. The delivery manifest's
`componentsFile` and `uiBuilderFile` must name that pair. This does not create a new publication
service: the existing delivery-branch/preview-server catalog transport reads it unchanged. A catalog
refresh must activate metadata and its matching renderer together. Retain catalog revisions and
runtime bytes required by saved design pins.

For live rendering, the catalog still builds and publishes its own `ui-builder/runtime.zip` and
`catalog.json.uiBuilderRuntime` descriptor, following compose-ui-builder's
[`UI_BUILDER_CATALOG_RENDERER_RUNTIME.md`](https://github.com/yschimke/compose-ui-builder/blob/main/docs/design/UI_BUILDER_CATALOG_RENDERER_RUNTIME.md).
The typed API supplies the registry, not a new runtime packaging pipeline. JSON alone cannot make
an external host execute a component it has never installed.

Browser builders use the existing sandboxed Wasm runtime. JVM/IDE hosts need installed adapters or
a project renderer process; the general addon loader is still a separate implementation task.
Android-only components use native rendering. The renderer SDK is currently a pinned source-build
dependency, not a publicly released artifact.

Source export continues to use the discovered callable and mappings. Opaque render lambdas are not
converted into Kotlin source. Use a discoverable export wrapper where the canvas call needs special
logic, and compile generated source against a separate consumer classpath before promising export
fidelity. Generated insertion defaults keep new designs explicit; hand-authored designs omitting
properties need their own render/export parity check. Kotlin metadata records whether a parameter
has a default, not its evaluated expression; the adapter default is an explicit catalog authoring
choice and is not compared with the composable default. Keep those defaults shared in app code or
test omitted-property rendering against exported-source behavior.

## Additional validation in an app

```kotlin
@Test
fun catalogStillMatchesTheApp() {
  val freshRecord = discoverOrReadCurrentComponentRecord()
  val current = freshRecord.components.single { it.canonicalId == button.component.canonicalId }
  assertEquals(emptyList(), button.validateAgainst(current))
  TypedAdapterCatalog.generate(freshRecord, cover, catalogPolicy, listOf(button))
}
```

JVM export applications can discover a deliberately selected set of compiled top-level Kotlin
callables with the public metadata-only helper:

```kotlin
val freshRecord = TypedAdapterDiscovery.discover(
  module = ":brand-components",
  variant = "desktop",
  callables = listOf(TypedAdapterDiscovery.Callable("com.acme.ButtonKt", "BrandButton")),
)
```

It scans the export process's runtime classpath, or an explicit `classpath` of compiled outputs.
Missing or overloaded entry points and unknown metadata are refused. It preserves source names
(including `@JvmName` mappings), defaults, slots and visibility without loading or invoking app
classes. Use an ordinary top-level export wrapper for member or receiver APIs. The record must
come from the current compiled source. Run this test after discovery and regenerate the pair in
the same build. It complements compilation by checking the published signature contract and
required-parameter coverage.

Real integration examples live in the opt-in `ui-builder-catalog` modules in
[meshcore-mobile](https://github.com/yschimke/meshcore-mobile) and
[homeassistant-remotecompose](https://github.com/yschimke/homeassistant-remotecompose).
Their validation scripts check the artifact pair in a separate builder process and compare the
real native component with its registered adapter. Native-only metadata does not promise browser
execution; shipping a compatible renderer is still required.

The repository's `TypedAdapterCompileTest` compiles a separate consumer against the real API, then
independently breaks a property name, property type, codec, callback payload and SDK method.
`TypedComponentAdapterTest` reads an actual compiled fixture's Kotlin metadata, verifies strict
decoding and publication selection, and writes/reloads the artifact pair.

From `gradle-plugin/`:

```sh
build-brief ../gradlew :preview-discovery:test --tests '*Typed*Adapter*Test'
```

The cross-repository validation script in compose-ui-builder also checks the existing host reader,
typed state/event dispatch, a real Compose component with a slot child, and Wasm compilation:

```sh
../compose-ui-builder/scripts/validate-typed-catalog.sh
```

No catalogue is registered or published by these tests. The next promotion step is to release this
tools API, bump the builder's tools pin, move its opt-in bridge into normal SDK sources, then finish
catalog runtime packaging and general JVM host installation as separate changes.
