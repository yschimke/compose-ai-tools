# Driving `compose-preview` from Amper

[Amper](https://github.com/JetBrains/amper) — JetBrains' declarative
build system with first-class Compose Multiplatform support — is the
shortest non-Gradle path to rendering `@Preview` composables. The
recipe below uses the three published `compose-preview` artifacts
(extracted in Phase A — see
[`ee.schimke.composeai:preview-discovery`](https://central.sonatype.com/artifact/ee.schimke.composeai/preview-discovery),
[`:daemon-launch-builder`](https://central.sonatype.com/artifact/ee.schimke.composeai/daemon-launch-builder),
[`:render-cli`](https://central.sonatype.com/artifact/ee.schimke.composeai/render-cli))
plus a shell wrapper around `./amper build`. No Gradle, no AGP.

## Minimal Compose Desktop fixture

The Amper module itself is six lines —
[`amper-cmp-desktop/module.yaml`](../amper-cmp-desktop/module.yaml)
in this repo is the canonical example, cribbed from JetBrains' upstream:

```yaml
# module.yaml
product: jvm/app

dependencies:
  - $compose.desktop.currentOs

settings:
  compose: enabled
  jvm:
    release: 17
```

`jvm.release: 17` is load-bearing — the `compose-preview` daemon runs
on JDK 17 and refuses class files compiled against newer JDKs with
`UnsupportedClassVersionError` (class version 65 ≠ 61). Amper's
default is `jvm.release: 21`.

`./amper build` produces:

| Artifact | Path |
| --- | --- |
| Compiled classes | `build/artifacts/CompiledJvmArtifact/<module>jvm/kotlin-output/` |
| Module jar | `build/tasks/_<module>_jarJvm/<module>-jvm.jar` |
| Resolved runtime jars | `~/.cache/JetBrains/Amper/.m2.cache/<group>/<artifact>/<version>/` |

## Three-step rendering pipeline

```
./amper build                              ← Amper produces compiled classes
  ↓
java -jar preview-discovery.jar            ← Phase A artifact #1
  → previews.json
  ↓
java -jar daemon-launch-builder.jar        ← Phase A artifact #2
  → daemon-launch.json
  ↓
java -jar render-cli.jar                   ← Phase A artifact #3
  → PNGs on disk
```

### 1. Discover previews

The published `ee.schimke.composeai:preview-discovery` jar scans
Amper's compiled output and writes a conforming `previews.json`:

```bash
java -cp <preview-discovery-and-deps> \
  ee.schimke.composeai.discovery.PreviewDiscoveryCli \
  --classes build/artifacts/CompiledJvmArtifact/<module>jvm/kotlin-output \
  --module <module> \
  --variant desktop \
  --project-directory . \
  --out build/compose-previews/previews.json
```

`<preview-discovery-and-deps>` is the resolved classpath for the jar
plus its transitives (`classgraph`, `asm`, `kotlinx-serialization`).
A wrapper script that calls Coursier (or any other Maven resolver) to
materialise that bag is the typical shape.

### 2. Synthesise the daemon descriptor

`daemon-launch-builder` takes the renderer-side classpath (resolved
via Amper's m2 cache or a separate Coursier call) and emits
`daemon-launch.json`:

```bash
java -cp <daemon-launch-builder-and-deps> \
  ee.schimke.composeai.daemonlaunch.DaemonLaunchBuilderCli \
  --module-path :amper-cmp-desktop \
  --variant desktop \
  --main-class ee.schimke.composeai.daemon.DaemonMain \
  --classpath <renderer-jar>:<connector-jars>:<compose-desktop-runtime>:<user-classes> \
  --jvm-arg -Xmx1024m \
  --system-property composeai.daemon.protocolVersion=1 \
  --system-property composeai.daemon.modulePath=:amper-cmp-desktop \
  --system-property composeai.daemon.workspaceRoot=$(pwd) \
  --system-property composeai.daemon.previewsJsonPath=$(pwd)/build/compose-previews/previews.json \
  --system-property composeai.render.outputDir=$(pwd)/build/compose-previews/renders \
  --working-directory $(pwd) \
  --manifest-path $(pwd)/build/compose-previews/previews.json \
  --out build/compose-previews/daemon-launch.json
```

The renderer-side jars are:

- `ee.schimke.composeai:daemon-desktop:<v>` (or `daemon-android` for
  Compose Android — needs Robolectric machinery; start with desktop).
- `ee.schimke.composeai:data-render-connector:<v>` and `-core` per
  data extension you want available.
- `ee.schimke.composeai:daemon-core:<v>`.
- The Compose runtime jars Amper already resolved.

Classpath order is load-bearing: the renderer jar leads so its
pinned-version classes win over consumer transitives.

### 3. Render

```bash
java -cp <render-cli-and-deps> \
  ee.schimke.composeai.render.cli.RenderCli \
  --descriptor build/compose-previews/daemon-launch.json \
  --workspace-root $(pwd) \
  --previews GreetingKt.Greeting \
  --timeout-seconds 60
```

Output on stdout: `GreetingKt.Greeting<TAB>/abs/path/Greeting.png`.

## Sandbox / TLS-inspection environments

In managed sandboxes with a TLS-inspection proxy, Amper's bundled Zulu
JRE doesn't trust the proxy CA and Maven Central / Google Maven fetches
fail with PKIX errors. Wire the system truststore explicitly:

```bash
export AMPER_JAVA_OPTIONS="-ea -XX:+EnableDynamicAgentLoading \
  -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts \
  -Djavax.net.ssl.trustStorePassword=changeit"
```

Harmless on a stock runner; necessary in TLS-inspecting environments.

## Limitations

- **Amper distribution.** Amper itself ships through
  `packages.jetbrains.team` (not Maven Central — tracked as
  [AMPER-471](https://youtrack.jetbrains.com/projects/AMPER/issues/AMPER-471));
  CI needs that host allowlisted.
- **Plugin model.** Amper 0.10 doesn't have first-class plugin support
  yet. The shape above is "shell scripts invoking `java -jar`
  between Amper task runs", not real Amper plugins. Revisit when
  Amper ships plugins.
- **Android rendering** needs the Android backend (`daemon-android`)
  + Robolectric on the classpath — heavier; not covered here. Start
  with Compose Desktop.

## See also

- The Amper Compose Desktop fixture: [`amper-cmp-desktop/`](../amper-cmp-desktop/)
- The Amper Android fixture: [`amper-android/`](../amper-android/)
- Contract spec (`daemon-launch.json` schema, classpath layering,
  sysprops): [`yschimke/compose-ai-tools/docs/NON_GRADLE_INTEGRATION.md`](https://github.com/yschimke/compose-ai-tools/blob/main/docs/NON_GRADLE_INTEGRATION.md)
