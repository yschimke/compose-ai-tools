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

Callback validation checks function arity, non-nullability (an event may fill an optional handler;
see below), the absence of composable/DSL receiver
flags, the qualified Unit return classifier and exact type tokens. Discovery's current callback
input spelling uses simple names; it cannot distinguish a domain `Boolean` from `kotlin.Boolean`
there. The compiled renderer call is the authoritative input-type check. Missing structural callback
metadata is refused; regenerate older records with current discovery.

`stateChange(Props::onCheckedChange, checked)` checks the callback payload against the property's
codec at compile time. The bridge delegates write-back and event execution to the existing SDK.
`slot("content")` creates a handle used by `Slot(adapter.content)` and by the generated slot
metadata; validation checks it names a real composable slot. Slot names are currently validated at
build/test time, not referenced as Kotlin parameters. Receiver-scoped layout logic remains ordinary
Compose in the renderer; lazy DSL slots and composable slots with parameters are outside this API.

The supplied scalar codecs are String, Boolean, Int and Float, plus colour, enum, unit and token
codecs for catalogs. A codec is invariant in its value type, converts
both directions, and identifies the exact Kotlin classifier it accepts. Defaults must round trip.
Malformed supplied values are errors rather than silently replaced defaults. String and Boolean
properties advertise state binding by default; `bindable = false` removes that alternative. Int
properties are literal-only: the existing state schema supports `number` but cannot constrain it to
an Int. Explicitly enabling Int binding or attaching a state callback to it is refused at declaration
rather than accepting fractional state that later fails decoding.

### List and object properties

`AdapterValueCodecs.list(element)` and `AdapterValueCodecs.obj(typeFqn) { … }` compose any codec,
scalars, lists and objects alike, to any depth:

```kotlin
data class Contact(val name: String, val unread: Int)

val ContactCodec: AdapterObjectCodec<Contact> =
  AdapterValueCodecs.obj("com.example.Contact") {
    val name = field(Contact::name, AdapterValueCodecs.String)
    val unread = field(Contact::unread, AdapterValueCodecs.Int, default = 0)
    construct { Contact(name = it[name], unread = it[unread]) }
  }

class InboxAdapter(record: ComponentRecord) : TypedComponentAdapter<InboxProps>("acme/inbox", record) {
  val contacts = property(InboxProps::contacts, AdapterValueCodecs.list(ContactCodec), emptyList())
}
```

Field handles are typed by the property reference, so `it[unread]` is an `Int`; a field with a
`default` may be absent from a stored object, one without is required, and an unknown field is
refused. Both are literal-only (never bindable). A codec stores a value through `wrap` and reads it
back through `unwrap`, nesting the document's wrappers rather than raw JSON:

```json
{"type": "list", "values": [ {"type": "object", "fields": {
  "name": {"type": "string", "value": "Ada"}, "unread": {"type": "int", "value": 2}}} ]}
```

A scalar's `wrap` is the `{type, value}` literal `insertContent` has always carried. The property's
capability publishes `jsonType` `"array"` / `"object"` as a plain string plus a recursive `editor`
hint, each element or field described by a shape of `jsonType`, `literalType`, optional
`allowedValues` (an enum or closed token set) and, for a nested list or object, its own `editor`;
object fields add `name`, `required` and, when declared, the `default` wrapper:

```json
{"name": "contacts", "jsonType": "array", "required": false,
 "editor": {"control": "list", "element": {"jsonType": "object", "literalType": "object",
   "editor": {"control": "object", "objectType": "com.example.Contact", "fields": [
     {"name": "name", "required": true, "jsonType": "string", "literalType": "string"},
     {"name": "unread", "required": false, "default": {"type": "int", "value": 0},
      "jsonType": "integer", "literalType": "int"}]}}}}
```

Validation compares a list property with the parameter's erased classifier
(`kotlin.collections.List`; the element type is the property reference's compile-time check) and an
object property with the class the codec names; a nullable parameter needs a `nullable` codec (see
below). Exported
through `from(property)`, a list is written `listOf(…)` and an object as a named-argument call to its
class (or the `callableFqn` given to `obj`), recursively. Every field is written: one a stored
object omits takes the default declared with `field(…, default = …)`, which the export codec carries,
since the class's constructor need not share it, and an omitted required field is refused. A nested
scalar must be exactly its codec's literal wrapper, as `unwrap` requires. A host's
`expressionPackages` must admit `kotlin.collections` and the object's package, which the
projection's `qualifiedOwners` lists.

### Longs, doubles, parsed text, nulls, sealed types and payload callbacks

These cover the model types real app components take, beyond text, numbers, lists and objects:

| Codec | Stored as | Exported as |
| --- | --- | --- |
| `AdapterValueCodecs.Long` | `{"type": "int", "value": 4294967296}` | `4294967296L` (a value in `Int` range as a plain literal) |
| `AdapterValueCodecs.Double` | `{"type": "number", "value": 59.437}` | `59.437` |
| `AdapterValueCodecs.Instant` | `{"type": "string", "value": "2026-10-12T08:00:00Z"}` | `Instant.parse("2026-10-12T08:00:00Z")` |
| `AdapterValueCodecs.parsed(type, parseFqn, format, parse, print)` | `{"type": "string", "value": "…"}` | `<parseFqn>("…")`, e.g. `PublicKey.fromHex("…")` |
| `AdapterValueCodecs.nullable(codec)` | `codec`'s wrapper, or `{"type": "null"}` | the value, or nothing for null |
| `AdapterValueCodecs.union(type) { … }` | `{"type": "union", "case": "<name>", "fields"?: {…}}` | the case's constructor call, or a reference to a `data object` |

`Long` is stored as an `int` wrapper and `Double` as a `number` one, so an editor needs nothing new
to author them; `Long` is literal-only like `Int`, and `Double` binds to a `number` state variable
like `Float`. `Long` holds ±(2^53 − 1), JavaScript's safe integers, and refuses anything larger: a
browser editor reading the document with `JSON.parse` would silently round it.

`parsed` is for a type with a text spelling of its own: an instant, a hex key, a byte string. It is
a `string` wrapper whose `shape` (and top-level capability) adds `"format"` (`date-time`, `hex`, …)
for an editor to check and pick a control by; `parse` must throw on malformed text, which is
refused like any malformed value. The export calls `parseFqn`, a top-level function or a member of
a classifier's companion taking one `String`, so the host's `expressionPackages` must admit its
owner (`kotlin.time` for `Instant`).

`nullable` matches a parameter or object field of the nullable type, and only that: validation
refuses a nullable codec on a non-null parameter and a plain codec on a nullable one. The shape and
capability add `"nullable": true`. A Kotlin call written by name passes null only by leaving the
argument out, so an export writes a stored null as nothing: the parameter, or the object field's
constructor parameter, must default to null. A null list element cannot be written that way and is
refused. A nullable value never binds to state. On a node itself, the builder already spells
"unset this property" as `setProperty` with `{"type": "null"}`, so a top-level null is the property
left unset, which reads as the adapter's default: give a nullable property a null default. Inside a
list, an object or a union case, `{"type": "null"}` is stored as the value it says.

`union` stores one case of a sealed type:

```kotlin
val StateCodec: AdapterValueCodec<LoadState> =
  AdapterValueCodecs.union("com.example.LoadState") {
    singleton("loading", LoadState.Loading, "com.example.LoadState.Loading")
    case("failed", FailedCodec) // an obj codec of LoadState.Failed
  }
```

A class case keeps its own `obj` codec and stores its `fields`; a `singleton` stores none and is
exported as a reference to the object. Its editor hint lists the cases, each with its `objectType`
and, for a class case, the same `fields` an object's editor has:

```json
{"control": "union", "unionType": "com.example.LoadState", "cases": [
  {"name": "loading", "objectType": "com.example.LoadState.Loading"},
  {"name": "failed", "objectType": "com.example.LoadState.Failed", "fields": [ … ]}]}
```

`event(Props::onPick)` also takes a callback of one or two arguments (`(Contact) -> Unit`,
`(String, Int) -> Unit`), returning an `AdapterEvent1` / `AdapterEvent2`: the canvas runs the
design's action and discards the arguments, since an action carries none. An optional handler,
`(() -> Unit)?` or `((Contact) -> Unit)?`, is an event too and is passed the action. Validation
checks the arity and the `Unit` return; the argument types are the property reference's, checked
when the adapter compiles. A payload event is not exported through `exportAs`'s `events`, which
write zero-argument lambdas, and declaring one there is refused.

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

## Publishing a typed catalog

`design-artifacts-reusable.yml` publishes a typed catalog onto the delivery branch beside the
sticker sheet when the caller names the task that generates it:

```yaml
uses: yschimke/compose-ai-tools/.github/workflows/design-artifacts-reusable.yml@<sha>
with:
  system: my-app
  artifact-repository: me/my-app-out          # optional: the generated branches' own repository
  ui-builder-catalog-task: ':ui-builder-catalog:exportTypedCatalog'
  ui-builder-catalog-dir: ui-builder-catalog/build/catalog
  # optional: the catalog's own Wasm renderer, built against a compose-ui-builder release
  ui-builder-runtime-task: ':ui-builder-catalog-renderer:rendererArchive'
  ui-builder-runtime-archive: ui-builder-catalog-renderer/build/ui-builder-runtime.zip
  ui-builder-runtime-source-ref: v3.109.0
secrets:
  artifacts_token: ${{ secrets.ARTIFACTS_TOKEN }}
```

The task writes `ui-builder.json`, the record it composes with as `components.json`, and optionally
a `ui-builder/` tree of the seed designs its policy's `templates` name. They are published as the
branch's `ui-builder.json`, `ui-builder/components.json` and `ui-builder/…`, and `catalog.json`'s
`uiBuilderFile` and `componentsFile` point at them: a server composes a published `ui-builder.json`
with the record `componentsFile` names, and the branch's top-level `components.json` is everything
discovery found, which would shelve every composable rather than the adapted ones. A renderer archive
is verified and declared (`uiBuilderRuntime`) exactly as for a policy-generated catalog. A catalog has
one builder definition, so a render that also generated a `ui-builder.json` from a
`ui-builder.policy.json` fails the step.
