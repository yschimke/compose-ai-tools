# Remote Compose JSON

**Status: normative for this repository's Remote Compose JSON handling.** What the format is, which
of it AndroidX provides, what this repository adds, and where each piece is allowed to live.

## The format is two formats

"The RemoteCompose JSON format" names two different things, they are not inverses, and every design
mistake in this area starts by conflating them.

| | **Authoring JSON** | **Document JSON** |
| --- | --- | --- |
| What it is | a source language | a projection of a compiled document |
| Written by | a person, a generator | this repository's `RemoteComposeJson.dump` |
| Read by | `RemoteComposeJsonParser` (AndroidX) | a human, `diff`, `jq` |
| Names things as | `"bg"`, `"fillMaxSize"`, `"@w / 2.0"` | `ColorConstant`, `WidthModifierOperation`, an RPN array |
| Specified by | AndroidX `remote_compose_schema.json` | this repository, and nothing reads it back |
| Round-trips? | to `.rc`, yes | no, and it never will |

```
authoring JSON  --compile-->  .rc (binary)  --dump-->  document JSON
                                    ^
                          also written by a real
                          render capturing a sticker
```

Dumping a compiled document does **not** give back the authoring JSON that produced it. Compiling
collapses names to integer ids, expands `fillMaxSize` into a `WidthModifierOperation` carrying a
NaN-encoded marker, and flattens the ordered modifier list into the operation stream. That is not a
gap to be closed later — it is what compilation is.

`RemoteComposeJson.dumpToJsonObject` refuses JSON text handed to it by name, because
`rc dump doc.json` is the shape this confusion takes at a terminal and the inflater's own failure
(`Path too long`, from reading `{"header":` as opcodes) points nowhere useful.

## What is upstream and what is ours

**Upstream provides the authoring direction and nothing else.** `RemoteComposeJsonParser` in
`androidx.compose.remote:remote-creation-core` parses authoring JSON into a document;
`remote_compose_schema.json` and `Documentation/parts/json-parser.md` in the AndroidX tree describe
what it accepts. There is no official `.rc` → JSON writer, in any AndroidX artifact.

**The dump is ours, but the walk is not.** `:remotecompose-json` does not read the wire format. It
inflates with `remote-core` — the same `CoreDocument` Android runs — and then walks the result
through `androidx.compose.remote.core.serialize.Serializable`, the structured hook every operation
already implements for AndroidX's own tooling, collecting it into a `JsonObject`.

That choice is the load-bearing one. The alternative, and what every third-party rc→JSON dumper is,
is a hand-written binary reader. Against this format that is the wrong trade twice: there are no
checksums and 300-odd opcodes that move between alphas, so a reader does not break when it drifts —
it keeps parsing, just wrongly, and the result renders as a plausible document that is not the one
on disk. Riding the upstream hook means an operation that gains a field gains it here for free, and
an operation upstream has not taught to serialize surfaces as `$unserialized` instead of as a value.

`Header` is the one operation that always lands under `$unserialized`; it predates the hook.
`RemoteComposeJson` decodes it properly into the dump's `header` key, so nothing is lost — seeing it
twice is expected, and anything *else* appearing there is a real gap worth reporting upstream.

## Non-finite floats are the interesting part

JSON has no `NaN`, and Remote Compose leans on NaN harder than any format worth naming: an id does
not travel as a number, it travels as a **NaN payload** (`Utils.asNan(id)` sets the `0xFF800000`
bits and packs the id into the mantissa). So `"width": [NaN, NaN]` is not a missing value — it is
two encoded references, and a dump that prints `NaN` destroys exactly what its reader opened it for.
A dump that prints `null` erases the difference between "fill" and "unset".

Every non-finite float is therefore a **string**: `"@42"` for a NaN carrying id 42, and `"NaN"` /
`"Infinity"` / `"-Infinity"` otherwise. `"@42"` deliberately does not say whether 42 is a colour, a
text or a float variable — the `NamedVariable` operations in the same dump do, and pairing them is
the reader's job rather than an invented type system this layer does not have.

## Placement

`:remotecompose-json` is **layer 1** by [`REPOSITORY_LAYERS.md`](REPOSITORY_LAYERS.md)'s test: it is
behaviour, and it opens no socket. compose-preview-server consumes it as a published coordinate,
the same shape `:render-matrix` and `:daemon-client` settled on.

It is its own module rather than a package in `:render-host` because of what it drags:
`remote-core` + `remote-creation-core` + `org.json` is 1.6 MB, and `:render-host` exists so an
offline caller does not link what it does not use.

**It is deliberately not in the Gradle plugin**, which is the first place a reader looks for it —
"surely the bundle should carry a `.rc.json` beside each `.rc`". The plugin is an isolated included
build whose code runs inside the *consumer's* build daemon. Putting the codec there would put the
Remote Compose runtime on the classpath of every project that applies the plugin, including projects
with no Remote Compose in them, and would give this repository a fourth place a split Remote Compose
family can come from — see [`RemoteComposePairing`](../../render-host/src/main/kotlin/ee/schimke/composeai/cli/serve/RemoteComposePairing.kt)
for what the existing three cost. A publish step calling `compose-preview rc dump <dir>` is the
cheaper seam and it needs no bundle schema version.

## Both directions run on a bare JVM

`remote-core` and `remote-creation-core` are plain `java-library` publications. Every *other*
artifact in the family — `remote-creation`, `remote-player-core`, `remote-player-view`,
`remote-tooling-preview` — is an Android AAR, and they all sit in the same `dependencyManagement`
block in the POM, so reaching one for a single helper class is a two-character edit that compiles
here and dies in a consumer's daemon with `NoClassDefFoundError: android/graphics/Paint`.
`RemoteComposeJsonJvmOnlyTest` asserts `android.graphics.Paint` is unloadable so that edit fails in
this module instead.

Two more consequences of the JVM target, neither visible from the coordinates:

- **`org.json` is not on the classpath and nothing declares it.** `RemoteComposeJsonParser` is
  written against `JSONObject`; Android supplies it from the platform, a JVM does not, and
  `remote-creation-core` neither shades nor declares it. `:remotecompose-json` does. Its absence is
  a runtime `NoClassDefFoundError`, not a resolution failure.
- **Compiling is not rendering.** `compile` runs on `RemoteComposeJsonParser.DEFAULT_PLATFORM`,
  whose **text measurement** is a stub. A document whose *layout* depends on measured text compiles
  fine and must still be measured by a real player before its bounds mean anything.

  **Path parsing is not a stub**, though it is easy to assume it is from the same sentence. The
  default platform's `parsePath` returns a real `RemotePathBase` and `PathParser.parsePathData`
  does the work, so `"M 10 10 L 90 10 L 90 90 Z"` compiles to the NaN-opcode float array a player
  draws — visible in a dump as `{"type": "PathData", "path": ["@10", 10.0, 10.0, "@11", …]}`, where
  `@10` / `@11` / `@15` are MOVE / LINE / CLOSE. Geometry is not lost by compiling off-device.

## The empty-document trap

`RemoteComposeJsonParser.parseToByteBuffer("{}")` **succeeds**, and returns a valid, playable,
17-byte header-only document. So does a **generation-library entry** — a file that wraps the real
document under a `json` key next to its prose metadata.

This is worth a whole section because of where it fails: nowhere. The bytes are a real document. The
bundle packs them, the daemon replays them, the preview renders blank, and no stage anywhere reports
an error. `RemoteComposeJson.compile` therefore spends a JSON parse to require `root` — the schema's
only `required` property — before handing anything to the parser, and names the wrapper case
separately because unwrapping is the fix and "missing root" does not suggest it.

## The locale trap in the batch dump

`File.listFiles()` decodes directory entries with `sun.jnu.encoding`, which follows the process
locale. Under `LANG=C` / `POSIX` that is `ANSI_X3.4-1968`, and **every filename holding a byte above
0x7F comes back with U+FFFD replacement characters** — a `File` whose `isFile()` is false, because
the mangled name resolves to nothing on disk.

This repository's preview ids do hold such bytes: they can carry an em-dash, which is why
`design-artifacts-reusable.yml` sets `LANG: C.UTF-8` and says so. So a batch dump under the wrong
locale reports *"no .rc documents under …"* for a directory full of them — a wrong answer that reads
exactly like a correct one, and one that would publish an empty `documents/` tree with nothing
anywhere reporting a problem. Measured, not theorised: an em-dash-named tree of four documents
listed as four unresolvable entries.

`rc dump <dir>` therefore refuses a tree containing an undecodable entry, naming the encoding and
the fix. The check is on entries that actually came back undecodable rather than on an
unfortunate-looking `sun.jnu.encoding`, so an ASCII-only tree is never refused for a hazard it does
not have.

## `rc dump <dir>` will not overwrite what it did not write

`<stem>.rc` dumps to `<stem>.rc.json`, and `<stem>.rc.json` is also a perfectly ordinary name for
the **authoring** JSON that produced it — the two dialects collide in the filesystem the same way
they collide in conversation. Overwriting is one-way harm: document JSON has no parser, so a
clobbered source cannot be recovered from the file that replaced it.

A target is therefore written only when it does not exist or is itself a previous dump, recognised
by the two keys every dump has and no authoring document does (`header` **beside** an `operations`
array). Anything else is left alone and reported, which costs a re-run at worst; guessing wrong
costs someone's file. Re-dumping stays idempotent, which is what the delivery lane needs.

## Command line

```
compose-preview rc compile <doc.json> -o <doc.rc>   authoring JSON -> binary document
compose-preview rc dump <doc.rc> [--compact] [-o f] binary document -> document JSON
compose-preview rc dump <dir> [--compact]           every .rc under <dir> -> <stem>.rc.json
compose-preview rc header <doc.rc> [--json]         declared header only, without inflating
```

The directory mode is how a catalog gets its documents into a published branch: `renders/` holds one
`.rc` per Remote preview, and an artifact branch carrying only PNGs gives a reviewer nothing to diff.
A picture diff cannot distinguish a changed padding from a changed antialiasing pass; the document
can. One unreadable document does not stop the batch — publishing thirty-nine documents and naming
the fortieth beats publishing none — but the command exits non-zero so a workflow still notices.
