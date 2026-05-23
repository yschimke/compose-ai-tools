# `contrib/` — moved to [`yschimke/compose-ai-contrib`](https://github.com/yschimke/compose-ai-contrib)

All non-Gradle integration code (Amper / Bazel fixtures, worked-example
docs, opt-in CI) lives in the dedicated
[`yschimke/compose-ai-contrib`](https://github.com/yschimke/compose-ai-contrib)
repo. The toolchain itself stays here — `compose-ai-contrib` consumes
the published Maven Central artifacts:

- `ee.schimke.composeai:preview-discovery` — ClassGraph scan + `previews.json` builder. Library API plus a [`PreviewDiscoveryCli`](../gradle-plugin/preview-discovery/src/main/kotlin/ee/schimke/composeai/discovery/PreviewDiscoveryCli.kt) entry point invoked as `java -cp <resolved-classpath> ee.schimke.composeai.discovery.PreviewDiscoveryCli …`. The published JAR is a slim library — the caller resolves `classgraph`, `asm`, `kotlinx-serialization`, and `kotlin-stdlib` through its own dep system, the same way it would for any other Maven library.
- `ee.schimke.composeai:daemon-launch-builder` — typed `daemon-launch.json` builder. Library API plus a [`DaemonLaunchBuilderCli`](../gradle-plugin/daemon-launch-builder/src/main/kotlin/ee/schimke/composeai/daemonlaunch/DaemonLaunchBuilderCli.kt) entry point invoked as `java -cp <resolved-classpath> ee.schimke.composeai.daemonlaunch.DaemonLaunchBuilderCli …`. Slim JAR; same classpath contract as `:preview-discovery`.
- `ee.schimke.composeai:render-cli` — thin CLI over [`SubprocessRenderSessions`](../render-session/subprocess/src/main/kotlin/ee/schimke/composeai/render/session/subprocess/SubprocessRenderSession.kt), invoked as `java -cp <resolved-classpath> ee.schimke.composeai.render.cli.RenderCli …`. Slim JAR; same classpath contract.
- `ee.schimke.composeai:preview-data-api` — published wire-format DTOs (`PreviewResult`, `PreviewManifest`, `ExtensionPayload`, the v1 a11y mirror types). Consumed by any tool that reads compose-preview JSON output. See [`preview-data-api/`](../preview-data-api/).
- `ee.schimke.composeai:gradle-preview-driver` — Gradle Tooling-API render pipeline as a library. `GradlePreviewDriver.render(...)` returns `PreviewResult` lists with PNG sha256s populated, no CLI dependency. See [`gradle-preview-driver/`](../gradle-preview-driver/).

> The three CLI artifacts above are published as slim library JARs — there is no fat/shadow JAR and no `Class-Path:` manifest entry, so `java -jar <artifact>.jar …` does **not** work standalone (it fails with `NoClassDefFoundError` on the first transitive class). Bazel `rules_jvm_external` and Amper task definitions already resolve the transitive runtime closure and pass it as `-cp`, which is the intended integration shape.

The `compose-preview-scripting <path.composepreview.kts>` binary (Kotlin-scripting host for
`previews()` / `show(id)` / `fail(msg)` DSL — issue #1084) lives in the contrib repo on top of
`:preview-data-api` + `:gradle-preview-driver`; it's the proof that features like scripting
don't need to be built into the main CLI.

The wire-stable contract (`daemon-launch.json` + `previews.json`
schemas, classpath layering, system properties) is documented in
[`docs/NON_GRADLE_INTEGRATION.md`](../docs/NON_GRADLE_INTEGRATION.md).
That file stays here — it's the published interface and belongs with
the published artifacts.

The migration history (Phase A extraction PRs, Phase B blueprint,
Phase C cutover) is recoverable from `git log -- contrib/`.
