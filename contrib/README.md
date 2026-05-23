# `contrib/` — moved to [`yschimke/compose-ai-contrib`](https://github.com/yschimke/compose-ai-contrib)

All non-Gradle integration code (Amper / Bazel fixtures, worked-example
docs, opt-in CI) lives in the dedicated
[`yschimke/compose-ai-contrib`](https://github.com/yschimke/compose-ai-contrib)
repo. The toolchain itself stays here — `compose-ai-contrib` consumes
the published Maven Central artifacts:

- `ee.schimke.composeai:preview-discovery` — ClassGraph scan + `previews.json` builder. Library API plus a [`PreviewDiscoveryCli`](../gradle-plugin/preview-discovery/src/main/kotlin/ee/schimke/composeai/discovery/PreviewDiscoveryCli.kt) entry point invoked as `java -cp <resolved-classpath> ee.schimke.composeai.discovery.PreviewDiscoveryCli …`. The published JAR is a slim library — the caller resolves `classgraph`, `asm`, `kotlinx-serialization`, and `kotlin-stdlib` through its own dep system, the same way it would for any other Maven library.
- `ee.schimke.composeai:daemon-launch-builder` — typed `daemon-launch.json` builder. Library API plus a [`DaemonLaunchBuilderCli`](../gradle-plugin/daemon-launch-builder/src/main/kotlin/ee/schimke/composeai/daemonlaunch/DaemonLaunchBuilderCli.kt) entry point invoked as `java -cp <resolved-classpath> ee.schimke.composeai.daemonlaunch.DaemonLaunchBuilderCli …`. Slim JAR; same classpath contract as `:preview-discovery`.
- `ee.schimke.composeai:render-cli` — thin CLI over [`SubprocessRenderSessions`](../render-session/subprocess/src/main/kotlin/ee/schimke/composeai/render/session/subprocess/SubprocessRenderSession.kt), invoked as `java -cp <resolved-classpath> ee.schimke.composeai.render.cli.RenderCli …`. Slim JAR; same classpath contract.

> The three CLI artifacts above are published as slim library JARs — there is no fat/shadow JAR and no `Class-Path:` manifest entry, so `java -jar <artifact>.jar …` does **not** work standalone (it fails with `NoClassDefFoundError` on the first transitive class). Bazel `rules_jvm_external` and Amper task definitions already resolve the transitive runtime closure and pass it as `-cp`, which is the intended integration shape.

The wire-stable contract (`daemon-launch.json` + `previews.json`
schemas, classpath layering, system properties) is documented in
[`docs/NON_GRADLE_INTEGRATION.md`](../docs/NON_GRADLE_INTEGRATION.md).
That file stays here — it's the published interface and belongs with
the published artifacts.

The migration history (Phase A extraction PRs, Phase B blueprint,
Phase C cutover) is recoverable from `git log -- contrib/`.
