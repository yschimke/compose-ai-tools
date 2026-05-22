# `contrib/` — moved to [`yschimke/compose-ai-contrib`](https://github.com/yschimke/compose-ai-contrib)

All non-Gradle integration code (Amper / Bazel fixtures, worked-example
docs, opt-in CI) lives in the dedicated
[`yschimke/compose-ai-contrib`](https://github.com/yschimke/compose-ai-contrib)
repo. The toolchain itself stays here — `compose-ai-contrib` consumes
the published Maven Central artifacts:

- `ee.schimke.composeai:preview-discovery` — ClassGraph scan + `previews.json` builder. Has a `java -jar` CLI ([`PreviewDiscoveryCli`](../gradle-plugin/preview-discovery/src/main/kotlin/ee/schimke/composeai/discovery/PreviewDiscoveryCli.kt)).
- `ee.schimke.composeai:daemon-launch-builder` — typed `daemon-launch.json` builder + CLI ([`DaemonLaunchBuilderCli`](../gradle-plugin/daemon-launch-builder/src/main/kotlin/ee/schimke/composeai/daemonlaunch/DaemonLaunchBuilderCli.kt)).
- `ee.schimke.composeai:render-cli` — thin CLI over [`SubprocessRenderSessions`](../render-session/subprocess/src/main/kotlin/ee/schimke/composeai/render/session/subprocess/SubprocessRenderSession.kt).

The wire-stable contract (`daemon-launch.json` + `previews.json`
schemas, classpath layering, system properties) is documented in
[`docs/NON_GRADLE_INTEGRATION.md`](../docs/NON_GRADLE_INTEGRATION.md).
That file stays here — it's the published interface and belongs with
the published artifacts.

The migration history (Phase A extraction PRs, Phase B blueprint,
Phase C cutover) is recoverable from `git log -- contrib/`.
