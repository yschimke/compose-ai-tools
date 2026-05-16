# Amper + Compose Preview — non-Gradle integration fixture

A tiny [Amper](https://amper.org) Compose Desktop project demonstrating
how to drive `compose-preview` from a non-Gradle build. The single
`@Preview` composable is `Greeting()` in [`src/Greeting.kt`](src/Greeting.kt);
the build is declared in [`module.yaml`](module.yaml) — six lines.

This fixture is consumer-style — `compose-ai-tools` does not vendor an
Amper wrapper into the repo (Amper's distribution lives on
`packages.jetbrains.team`, not Maven Central, so the wrapper would only
run in environments that allowlist that host). Downstream consumers
follow the [Amper quick-start](https://amper.org/) to bootstrap their
own wrapper, then point it at this module.

## End-to-end flow

```bash
# 1. Build with Amper (cold start ~60s the first time; ~3s cached).
./amper build

# 2. Discover @Preview composables in the build outputs. The discovery
#    library extraction is tracked separately; for now, point ClassGraph
#    at <module>/build/.../classes/main and emit previews.json by hand.
./scripts/discover-previews.sh \
  --module-classes build/tasks/_amper-cmp-desktop_jvmRuntimeClasspath/classes/main \
  --output build/compose-previews/previews.json

# 3. Synthesise daemon-launch.json from Amper's resolved classpath plus the
#    renderer / connector jars resolved out of Maven Central.
./scripts/amper-to-daemon-launch.sh \
  --module-yaml module.yaml \
  --classes build/tasks/_amper-cmp-desktop_jvmRuntimeClasspath/classes/main \
  --runtime-classpath build/tasks/_amper-cmp-desktop_jvmRuntimeClasspath/classpath.txt \
  --compose-preview-version 0.10.x \
  --output build/compose-previews/daemon-launch.json

# 4. Render via the published render-session-subprocess artifact.
kotlin -classpath /path/to/render-session-subprocess.jar \
  -e 'SubprocessRenderSessions.open(...)' # see the JVM snippet in NON_GRADLE_INTEGRATION.md
```

The helper scripts above are sketched in [`docs/NON_GRADLE_INTEGRATION.md`](../../docs/NON_GRADLE_INTEGRATION.md);
they're not vendored in this repo because Amper's output path layout is
still evolving (Amper 0.10 changed it from earlier versions). Pin to a
specific Amper release before scripting.

## Why this isn't a Gradle subproject

The rest of `compose-ai-tools/samples/` consists of regular Gradle
modules included via `settings.gradle.kts`. This directory deliberately
isn't included — the whole point is to demonstrate a project layout
that *doesn't* have a `build.gradle.kts`. The root `settings.gradle.kts`
skips this directory; the contract test
([`render-session/subprocess/.../NonGradleContractTest.kt`](../../render-session/subprocess/src/test/kotlin/ee/schimke/composeai/render/session/subprocess/NonGradleContractTest.kt))
proves the design end-to-end against `:samples:cmp`'s build outputs.

## Updating from upstream

The fixture mirrors
[`JetBrains/amper:examples/compose-desktop`](https://github.com/JetBrains/amper/tree/main/examples/compose-desktop)
with `material3` substituted for `material`. When upstream Amper
revises the example or bumps Compose, refresh this fixture in lockstep.
