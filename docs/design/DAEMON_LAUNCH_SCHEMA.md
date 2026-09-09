# The daemon-launch descriptor schema gate

`<module>/build/compose-previews/daemon-launch.json` is written by the Gradle plugin and read by the
daemon JVM, `compose-preview doctor`, and the VS Code extension — several representations of one
file, spread across separate Gradle builds, with nothing in the build knowing they describe the same
thing. `./gradlew :cli:checkDaemonLaunchSchema` is what knows.

**Its scope is Kotlin.** The task's inputs are `**/*.kt` and `walk_sources()` reads nothing else, so
what it covers is this repository's Kotlin representations plus the ones in a
`compose-preview-contracts` checkout when `COMPOSE_PREVIEW_CONTRACTS_ROOT` resolves one. The VS Code
extension's reader lives in [`yschimke/compose-preview-vscode`](https://github.com/yschimke/compose-preview-vscode)
and is **not** covered — it owns its own check. Treat a green run here as saying the Kotlin copies
agree, never that the extension does.

The schema version is the sharp edge. It was declared four times, as a literal `2` each time, and
two of those copies carried a comment asking a human to remember:

```
render-session/subprocess/.../SubprocessRenderSession.kt
    /** ... mirrors the gradle plugin's writer. */
    private const val DAEMON_DESCRIPTOR_SCHEMA_VERSION = 2

cli/.../McpCommand.kt
    // Keep in sync — bump together.
    internal const val EXPECTED_DESCRIPTOR_SCHEMA_VERSION: Int = 2
```

Bumping the writer alone leaves both writing and expecting v2 with nothing failing, while
`daemonProcess.ts` — the only reader that checks the version — rejects every descriptor the plugin
produces. `:render-session-subprocess` is one of the published contract modules the extracted server
links against, so that is not a same-commit mistake but cross-repo version skew no compiler sees.

`scripts/check-daemon-launch-schema.py` enforces ten things:

- **version agreement** — every declared copy equals the writer's, *and* every copy is registered.
  An unregistered version constant anywhere in the tree fails, so a new mirror has to be declared,
  which is the moment someone can ask whether it should exist at all. Discovery works **by use**
  as well as by name: every `schemaVersion = …` at a descriptor construction site must be a
  registered constant named as `schemaVersion = …` (a positional argument names nothing, so
  neither scan can see it), never a bare literal. That arm exists because name matching alone was not
  enough — `ServeBundleDaemon` calls its copy `DAEMON_LAUNCH_SCHEMA_VERSION` rather than
  `…DESCRIPTOR_SCHEMA_VERSION`, and stamps real descriptors with it, so a fifth mirror sat outside
  a check whose entire claim was that every mirror is registered;
- **structural agreement** — every field a reader requires is one the writer emits, shared fields
  carry corresponding types across the registered Kotlin copies, and a reader-only field is
  optional;
- **`BtaCompileConfig` field-for-field** across the copies that declare it, which its KDoc claimed
  and nothing enforced;
- **unknown-key tolerance** — the JVM reader keeps `ignoreUnknownKeys = true`, the single line that
  makes the writer's `btaCompile` (which that reader does not declare) safe rather than fatal;
- **mirrored constants** — sysprop keys the descriptor carries that other modules re-declare
  privately, such as `SANDBOX_COUNT_PROP`, whose own KDoc says "both sides MUST agree". Not only
  Kotlin ones: the production image passes that key as a literal in `deploy/image/Dockerfile`'s
  `JAVA_TOOL_OPTIONS`, so renaming it across every Kotlin copy would still strand deployed hosts on
  a property nothing reads, silently falling back to a pool of one;
- **raw-key readers** — `compose-preview doctor` indexes the parsed JSON by string rather than
  deserialising a DTO, so a renamed field leaves it asking for a key that reads as `null` and
  reporting the daemon disabled instead of failing. Its keys are checked against the writer, and
  each one records the **type it assumes** — `enabled` going `Boolean` -> `String` would keep its
  name, pass every other rule, and crash `.jsonPrimitive.boolean` at runtime with no compiler in
  the way;
- **annotation refusal** — an annotation can change what kotlinx.serialization emits while the
  declaration every other rule reads stays identical: `@SerialName` moves the key, `@Transient`
  drops the field, `@EncodeDefault` changes whether a default is written. Refused as a *class*
  rather than one at a time — nothing is allowed on these properties unless it is on an
  (currently empty) allowlist, so the next annotation someone reaches for has to be considered
  rather than slipping through;
- **the writers' encoder configuration** — the two production writers each configure their own
  `Json`, and that is part of the wire format rather than a formatting preference: a
  `namingStrategy` would rename every key while the declaration, the digest, both readers and every
  version constant stayed identical. Two hand-maintained copies of one contract is the same
  duplication this check exists for, one level out, so they are compared against the record and
  thereby against each other;
- **a wire fingerprint** — a digest of the writer's field names, types and optionality *and of the
  DTOs it nests*, recorded per version and **immutable once written**, pinned
  against the version it describes. Version agreement across the copies is necessary and not
  sufficient: a PR could rename a field in the writer and every in-repo reader at once, leaving
  all constants at v2 while a released extension accepts the new descriptor as v2 and misreads
  it. Changing the shape fails until someone records the new digest, which is where the question
  "is this breaking?" actually gets asked. Nesting is load-bearing: a top-level-only digest left
  `btaCompile` as the opaque token `BtaCompileConfig?`, so a rename *inside* that class moved the
  wire contract while the digest held steady. Immutability is the other half: while the digest was
  editable in place, a breaking rename could pass by updating the writer, the readers *and* the
  record together, leaving released v2 consumers accepting a v2 descriptor they now misread.
  Changing the shape means adding a version, never refreshing the entry for an existing one.

Divergences that are correct by design live in `scripts/daemon-launch-schema-allowlist.json` with
the reason written down — the same debt-register discipline as the seam allowlist, not an exemption
list. The three that exist today: serve's sandbox-only `jailCommand` and `hardTtlSeconds` (which
the plugin never writes, so both must default), and the writer's `btaCompile` (which the daemon JVM
does consume, but through flattened system properties rather than the nested block).

Every rule was falsified — each made to fail on purpose, then restored — and the gate is
unit-tested by `scripts/test_check_daemon_launch_schema.py`.

Two reachability notes, both of which made earlier versions of this weaker than they looked. The
Gradle task declares **every** Kotlin source as its input, not just the representations:
the repo-wide rule reads files nobody listed, so a narrower input set let Gradle call the task
up-to-date on precisely the change that added a new mirror. And because CI runs `test` tasks rather
than `check`, the enforcement that actually runs on a PR is `test_check_daemon_launch_schema.py` in
the `Actions Script Tests` job — which is why `ci-paths.json` scopes that job to `**/*.kt`. A
repo-wide claim is only worth as much as the trigger that runs it.

The extension's TypeScript reader is outside all of this, and that is a boundary rather than a gap:
it ships from its own repository on its own cadence, so a mirror added there is its check to catch.
The cost is real and worth stating — `daemonProcess.ts` is the only reader that verifies the schema
version, and nothing in this repository can tell you when it stops agreeing.

The representations agreed when the gate landed. That is the point: it was written while the
answer was still "no drift", so the first time it fails will be the first real drift.


---

Split out of `PREVIEW_SERVER_SPLIT.md`, where this gate was documented as one of four checks
policing the server seam. The other three were removed when the extraction completed; this one
polices a contract that has no module at all, and is unrelated to the split.
