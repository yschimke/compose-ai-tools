# Lifting `contrib/` to `yschimke/compose-ai-contrib`

This directory is the staging area for [`yschimke/compose-ai-contrib`](https://github.com/yschimke/compose-ai-contrib)
— the planned dedicated repo for non-Gradle integrations of
`compose-preview`. **Phase A (extracting the publishable hooks) is
complete on `yschimke/compose-ai-tools:main`.** This file is the
shopping list for **Phase B**: create the new repo and drop in
everything from `contrib/`.

## Status

| Phase | What | State |
| --- | --- | --- |
| A1 | `:preview-discovery` (schema + scan + CLI) | **done** — `ee.schimke.composeai:preview-discovery` on Maven Central |
| A2 | `:daemon-launch-builder` (descriptor + builder + CLI) | **done** — `ee.schimke.composeai:daemon-launch-builder` |
| A3 | `:render-cli` (CLI over render-session-subprocess) | **done** — `ee.schimke.composeai:render-cli` |
| B  | Bootstrap `yschimke/compose-ai-contrib` consuming the artifacts above | **this file — pending repo creation** |
| C  | Delete `contrib/`, lifted workflows, and worked-example sub-sections from this repo | **waiting on B** |

## What to lift

When `yschimke/compose-ai-contrib` is created, copy these files
verbatim — the new repo's tree mirrors this directory exactly:

```
contrib/                      compose-ai-contrib/
├── amper-android/        ─►  amper-android/
├── amper-cmp-desktop/    ─►  amper-cmp-desktop/
├── bazel/                ─►  bazel/
├── bazel-apk/            ─►  bazel-apk/
├── docs/                 ─►  docs/
│   ├── amper.md          ─►    amper.md
│   └── bazel.md          ─►    bazel.md
├── .github/              ─►  .github/
│   └── workflows/        ─►    workflows/
│       ├── amper-android.yml  amper-android.yml
│       └── bazel.yml          bazel.yml
└── SETUP.md              ─►  README.md  (or merge into project README)
```

Then create the repo's Gradle root from [`SETUP.md`](SETUP.md) — the
file documents the exact `settings.gradle.kts` / `build.gradle.kts` /
`gradle/libs.versions.toml` to drop in.

## Phase A — what got extracted

The three published artifacts a non-Gradle build system needs:

| Artifact | Coords | What it does |
| --- | --- | --- |
| `preview-discovery` | `ee.schimke.composeai:preview-discovery:<v>` | ClassGraph scan over compiled classes; writes `previews.json`. Has both a Kotlin API and a `java -jar` CLI (`ee.schimke.composeai.discovery.PreviewDiscoveryCli`). |
| `daemon-launch-builder` | `ee.schimke.composeai:daemon-launch-builder:<v>` | Typed builder + serializer for `daemon-launch.json`. Kotlin API + CLI (`ee.schimke.composeai.daemonlaunch.DaemonLaunchBuilderCli`). |
| `render-cli` | `ee.schimke.composeai:render-cli:<v>` | Thin CLI over `SubprocessRenderSessions` — drives the daemon JVM and prints rendered PNG paths. Main class `ee.schimke.composeai.render.cli.RenderCli`. |

A Bazel rule or Amper task chains these three jars:

```
discover → previews.json   (preview-discovery)
build descriptor → daemon-launch.json   (daemon-launch-builder, takes resolved jars + sysprops)
render → PNGs   (render-cli, reads both files above)
```

No Gradle. No AGP. Just three published Maven artifacts and a JDK.

## Phase B checklist (do once the repo exists)

- [ ] Create `yschimke/compose-ai-contrib` (public, empty).
- [ ] `cp -r contrib/* <new-repo>/` (everything except this file —
      `contrib/README.md` is the migration plan, not consumer docs).
- [ ] Apply the Gradle scaffolding from [`SETUP.md`](SETUP.md).
- [ ] Bootstrap CI: enable Actions, the lifted `amper-android.yml` /
      `bazel.yml` start running on push automatically.
- [ ] Open a PR back into this repo doing Phase C (delete the staged
      content here once compose-ai-contrib is operational).

## Phase C — what gets deleted from this repo

After Phase B succeeds:

- `contrib/amper-android/` / `amper-cmp-desktop/` / `bazel/` / `bazel-apk/`
- `.github/workflows/amper-android.yml` / `bazel.yml`
- `render-session/subprocess/src/test/.../AmperContractTest.kt`
  (replaced by a Maven-Central-consuming equivalent in compose-ai-contrib)
- The "Worked example: JetBrains Amper" and "Worked example: Bazel"
  sub-sections of [`docs/NON_GRADLE_INTEGRATION.md`](../docs/NON_GRADLE_INTEGRATION.md);
  replace with a one-line link to compose-ai-contrib's `docs/`.

The contract sections of `NON_GRADLE_INTEGRATION.md` (the
`daemon-launch.json` schema spec, classpath layering, sysprops) stay
here — those are the published interface and belong with the published
artifacts.

## Decisions locked in earlier

These were captured in this file's first draft (Phase A1 era) and
remain in force:

1. **`preview-discovery` ships library + CLI main.**
2. **`daemon-launch-builder` is generic — "assemble a descriptor" only.**
   Android-specific classpath layering (`AndroidPreviewClasspath`'s AGP
   `artifactView` resolution, R.jar appending, the
   Robolectric-on-JDK-17 `--add-opens` set) stays in `:gradle-plugin`.
3. **Amper integration is shell-scripts-invoking-`java -jar`, not
   plugins.** Amper 0.10's extension model isn't there yet
   ([AMPER-471](https://youtrack.jetbrains.com/projects/AMPER/issues/AMPER-471))
   and waiting for it blocks adoption.
4. **Separate `:render-cli`, not `DaemonMain --render-once`.** Keeps
   the daemon JSON-RPC-only.
