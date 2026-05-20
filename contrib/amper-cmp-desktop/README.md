# Amper + Compose Preview — non-Gradle integration fixture

A tiny [Amper](https://amper.org) Compose Desktop project demonstrating
how to drive `compose-preview` from a non-Gradle build. The single
`@Preview` composable is `Greeting()` in [`src/Greeting.kt`](src/Greeting.kt);
the build is declared in [`module.yaml`](module.yaml). The Amper wrapper
(`amper` + `amper.bat`) is vendored at the root so the fixture is
self-contained.

End-to-end is exercised by
[`AmperContractTest`](../../render-session/subprocess/src/test/kotlin/ee/schimke/composeai/render/session/subprocess/AmperContractTest.kt):
it consumes Amper's compiled output, synthesises a `daemon-launch.json`,
and drives a real `RenderSession` against it.

## Running the fixture

```bash
cd contrib/amper-cmp-desktop

# Sandbox / managed environments with a TLS-inspection proxy: the
# bundled Zulu JRE Amper auto-downloads doesn't trust the proxy CA, so
# Maven Central / Google Maven fetches fail with PKIX errors. Wire the
# system truststore (which the sandbox keeps current) explicitly:
export AMPER_JAVA_OPTIONS="-ea -XX:+EnableDynamicAgentLoading \
  -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts \
  -Djavax.net.ssl.trustStorePassword=changeit"

./amper build
```

Cold start (first run) downloads Amper (~12s) plus the Compose Desktop
runtime into Amper's m2 cache (~10s more); subsequent builds are
sub-3s.

## End-to-end with `compose-preview`

After `./amper build` the test runner picks the fixture up:

```bash
./gradlew :render-session-subprocess:test \
  --tests 'ee.schimke.composeai.render.session.subprocess.AmperContractTest'
```

The test self-skips when Amper outputs aren't on disk (so a clean tree
doesn't fail). It produces a PNG of `Greeting()` rendered through the
same daemon pipeline the Gradle plugin uses — the path that's exercised
is:

1. `./amper build` produces `GreetingKt.class` under
   `build/artifacts/CompiledJvmArtifact/amper-cmp-desktopjvm/kotlin-output/`.
2. The test reads the renderer + connector + Compose Desktop jar bag
   from `:samples:cmp:composePreviewDaemonStart`'s descriptor (a real
   Amper user would resolve these from Maven Central; `NonGradleContractTest`
   already covers the descriptor-synthesis side of the contract).
3. cmp's user-class dirs are filtered out and replaced with Amper's
   `kotlin-output/`; `previews.json` is hand-authored for
   `GreetingKt.Greeting`.
4. `SubprocessRenderSessions.open(...)` drives the daemon; `renderNow`
   produces a PNG asserted on disk.

## Why the fixture overrides `jvm.release`

Amper defaults to `jvm.release: 21`, but compose-preview's daemon JVM
runs on JDK 17 (the project's pinned toolchain). Loading a class
compiled against Java 21 in a Java-17 JVM fails with
`UnsupportedClassVersionError` (class file version 65 ≠ 61). The
fixture pins `jvm.release: 17` in `module.yaml` to bridge the gap;
downstream Amper users with a JDK 21+ daemon don't need this.

## Why this isn't a Gradle subproject

The rest of `compose-ai-tools/samples/` consists of regular Gradle
modules included via `settings.gradle.kts`. This directory deliberately
isn't included — the whole point is to demonstrate a project layout
that *doesn't* have a `build.gradle.kts`. The root `settings.gradle.kts`
skips this directory; the test above proves the design end-to-end
against Amper's outputs.

## Build-output layout (Amper 0.10)

For anyone scripting a non-Gradle integration of their own, the
relevant paths after `./amper build` are:

| Artifact | Path |
| --- | --- |
| Compiled classes | `build/artifacts/CompiledJvmArtifact/<module>jvm/kotlin-output/` |
| Module jar | `build/tasks/_<module>_jarJvm/<module>-jvm.jar` |
| Resolved runtime jars | `~/.cache/JetBrains/Amper/.m2.cache/<group>/<artifact>/<version>/` |

There is no resolved `classpath.txt` — a real integration would scrape
`./amper show dependencies` against the m2 cache, or use Amper's task
outputs once a stable API for that ships.

## Updating from upstream

The fixture mirrors
[`JetBrains/amper:examples/compose-desktop`](https://github.com/JetBrains/amper/tree/main/examples/compose-desktop)
with `material3` substituted for `material` (Greeting.kt uses
`material3.MaterialTheme` so the fixture lines up with the rest of
compose-ai-tools' Compose Desktop fixtures). When upstream Amper
revises the example or bumps Compose, refresh this fixture in lockstep,
including the wrapper scripts (pinned to Amper v0.10.0).
