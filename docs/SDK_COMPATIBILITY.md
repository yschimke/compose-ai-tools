# SDK compatibility matrix

This page documents which `(JDK × compileSdk × targetSdk × minSdk × composePreview.sdkVersion)`
combinations the renderer supports, which need the `composePreview.sdkVersion` override knob,
and which are expected-to-fail with the reason each one fails.

A nightly workflow ([`.github/workflows/sdk-matrix.yml`](../.github/workflows/sdk-matrix.yml))
exercises every cell listed below against the synthetic
[`:samples:sdk-matrix`](../samples/sdk-matrix) module — a single `@Preview` composable that
renders the runtime-observed `Build.VERSION.SDK_INT`, `Build.VERSION.RELEASE`, and
`applicationInfo.targetSdkVersion` so each cell's captured PNG is self-documenting. The
workflow's job summary surfaces a pass/fail table; the aggregator job fails if any cell's
outcome drifts from the expectation below.

## How the two known failure modes interact

Two separate constraints can break a Robolectric preview render. Distinguishing them is the
whole point of this matrix:

1. **Robolectric × JDK constraint.** Robolectric 4.16.x refuses to bootstrap an Android SDK 36
   sandbox unless the test JVM is JDK 21+ (`DefaultSdkProvider.verifySupportedSdk`). On JDK 17,
   any cell that resolves Robolectric's `sdk=` to 36 fails *at sandbox boot* with
   `Android SDK 36 requires Java 21 (have Java 17)` — before any preview body runs and
   independent of `minSdk` / `targetSdk` / the consumer's manifest. The fix is to bump the test
   toolchain to JDK 21, or to pin `composePreview.sdkVersion = 35` so Robolectric stays inside
   JDK 17's window.
2. **PackageParser × minSdk constraint** (the original [issue #1248](https://github.com/yschimke/compose-ai-tools/issues/1248)).
   Once Robolectric *does* bootstrap, it parses the consumer's `apk-for-local-test.ap_` through
   Android's `PackageParser`. If the manifest's `<uses-sdk android:minSdkVersion="N">` is
   *newer* than the framework Robolectric synthesized, parsing fails with
   `Requires newer sdk version #N (current version is #M)`. The fix is to either lower
   `minSdk`, or pin `composePreview.sdkVersion` to a level that satisfies it (which then
   re-introduces constraint #1 if you bump too high on JDK 17).

The matrix exercises both, with cells deliberately crossing each constraint's threshold.

## Matrix axes

| Axis | Values | Why |
| --- | --- | --- |
| **JDK** | 17, 21 | Constraint #1 above. The project itself runs on JDK 17 (per [`docs/AGENTS.md`](AGENTS.md)), but consumers can be on either. |
| **compileSdk** | 35, 36, 37 | 35 is the Play Store new-app target floor since Aug 2025; below that is non-sensical for any active app. 36 is the current AGP default. 37 covers consumers pulled forward by transitive `minCompileSdk` requirements (e.g. `wear-tiles-renderer`, `compose-remote` alpha). |
| **targetSdk** | 35, 36, 37 (subset) | Constrained to `targetSdk ≤ compileSdk` per AGP. The matrix doesn't include combinations like `targetSdk = 24, compileSdk = 36` — implausible for any active app. |
| **minSdk** | 24, 36 | The PackageParser check is gated by `minSdk`. The matrix spans low-floor (`24`, typical consumer default) and tightly-targeting (`36`, an app explicitly requiring the latest framework). |
| **`composePreview.sdkVersion`** | unset (auto-detect), `35` | The override knob added by [#1254](https://github.com/yschimke/compose-ai-tools/pull/1254). `unset` exercises the auto-detect path (`compileSdk` → `sdk=N` in `robolectric.properties`); `35` is the rescue value JDK 17 consumers reach for. |
| **Robolectric** | stable `4.16.1`, snapshot `4.17-SNAPSHOT` | The stable line tops out at API 36 and `GenerateRobolectricPropertiesTask.MAX_SUPPORTED_SDK` clamps `compileSdk` values above 36 to that ceiling for production consumers. Snapshots from upstream `master` (see commit [`0e89b68`](https://github.com/robolectric/robolectric/commit/0e89b684f5871ae6c65f973bc34aa022ec9a541e)) carry API 37 fixes ahead of the next release; the matrix's snapshot cells pair `-Pcomposeai.matrix.robolectricVersion=4.17-SNAPSHOT` with the matrix-internal `-Pcomposeai.matrix.maxSupportedSdk=37` escape hatch to lift the clamp alongside the snapshot. **Don't reach for the maxSupportedSdk knob in production builds** — without a paired Robolectric that ships the higher API, lifting the clamp turns the current build-time warning into a worse runtime sandbox failure. |

## Expectation table

`expected: pass` cells render cleanly. `expected: fail` cells document an intentional
limitation; the nightly workflow fails the aggregator job if any outcome drifts from these
expectations.

| JDK | compileSdk | targetSdk | minSdk | sdkVersion | Robolectric | Expected | Why |
|---:|---:|---:|---:|---|---|---|---|
| 17 | 35 | 35 | 24 | auto | 4.16.1 | ✅ pass | Baseline: auto-detect resolves to `sdk=35`, inside JDK 17's window, manifest minSdk is below the runtime framework. |
| 17 | 36 | 36 | 24 | auto | 4.16.1 | ❌ fail | **Constraint #1.** Auto-detect resolves to `sdk=36`; Robolectric refuses to bootstrap on JDK 17. Fix: bump toolchain to JDK 21, OR set `composePreview.sdkVersion = 35`. |
| 17 | 37 | 37 | 24 | auto | 4.16.1 | ❌ fail | Above-ceiling clamp lands at `sdk=36`; same JDK refusal as the `compileSdk=36` row. The clamp warning fires regardless. |
| 17 | 36 | 36 | 24 | 35 | 4.16.1 | ✅ pass | Rescue path: override pins Robolectric to SDK 35, which JDK 17 supports. Manifest `minSdk=24` ≤ 35, so PackageParser is happy. |
| 17 | 36 | 36 | 36 | 35 | 4.16.1 | ❌ fail | **Constraint #2 — the original #1248 shape.** Override pins Robolectric to SDK 35 (JDK 17 safe), but the manifest's `<uses-sdk minSdkVersion="36">` requires runtime ≥ 36, so PackageParser refuses. Only fix on JDK 17 is to lower `minSdk`. To stay on `minSdk=36`, the consumer needs JDK 21 + auto-detect. |
| 21 | 35 | 35 | 24 | auto | 4.16.1 | ✅ pass | Parity sanity check at the bottom of the range. |
| 21 | 36 | 36 | 24 | auto | 4.16.1 | ✅ pass | JDK 21 unlocks SDK 36; auto-detect picks `sdk=36` and renders. |
| 21 | 36 | 36 | 36 | auto | 4.16.1 | ✅ pass | **The #1248 case JDK 21 fixes outright.** Runtime SDK matches manifest `minSdk`, PackageParser passes, no override needed. |
| 21 | 37 | 37 | 24 | auto | 4.16.1 | ⚠️ pass (clamp) | Above-ceiling clamp lands at `sdk=36`; warning fires. `minSdk=24` ≤ 36 so PackageParser is happy. Production behaviour — consumers don't need the snapshot probe to get a working render. |
| 21 | 37 | 37 | 36 | auto | 4.16.1 | ⚠️ pass (clamp) | Same clamp behaviour; `minSdk=36` ≤ 36 so PackageParser is still happy. |
| 21 | 37 | 37 | 24 | auto | 4.17-SNAPSHOT | ✅ pass | **Snapshot probe.** Pairs `composeai.matrix.robolectricVersion=4.17-SNAPSHOT` with the matrix-internal `composeai.matrix.maxSupportedSdk=37` escape hatch so the task's clamp doesn't fire and the snapshot's API 37 fixes ([commit `0e89b68`](https://github.com/robolectric/robolectric/commit/0e89b684f5871ae6c65f973bc34aa022ec9a541e)) get exercised. PNG shows `SDK_INT = 37`. Drops back to ❌ if the snapshot regresses or stops shipping API 37. |
| 21 | 37 | 37 | 36 | auto | 4.17-SNAPSHOT | ✅ pass | Snapshot probe with minSdk at the boundary; `minSdk=36` ≤ runtime SDK 37 so PackageParser is happy. |

## Combinations the matrix deliberately does not cover

| Excluded combo | Why |
| --- | --- |
| `targetSdk < 35` | Play Store policy: new apps must target SDK 35+ since Aug 2025 and updates since Nov 2025. Any active project has `targetSdk ≥ 35`. |
| `compileSdk < 35` | AGP requires `compileSdk ≥ targetSdk`, and the previous row pins `targetSdk ≥ 35`. |
| `minSdk > targetSdk` | AGP rejects this at configuration time. |
| `compileSdk > 37` | Out of Robolectric 4.16.1's range; the plugin's `MAX_SUPPORTED_SDK = 36` clamp covers everything ≥ 36 identically. Worth a dedicated cell only when Robolectric ships API 37+. |
| Cross-product of every `sdkVersion` override with every cell | Once the override has been shown to rescue / preserve the constraint #2 fail on JDK 17, repeating it on JDK 21 adds no signal — the plugin's resolver is unit-tested in `GenerateRobolectricPropertiesTaskTest`. |

## How to use the override knob

When a cell in your project lands in the ❌ row, your options are:

1. **Bump the test toolchain to JDK 21.** Required if you need `minSdk ≥ 36` — only JDK 21
   lets Robolectric synthesize a SDK 36 framework. Add to your module's `build.gradle.kts`:

   ```kotlin
   kotlin { jvmToolchain(21) }
   tasks.withType<Test>().configureEach {
     javaLauncher.set(
       javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
     )
   }
   ```

2. **Pin Robolectric to a lower SDK explicitly:**

   ```kotlin
   composePreview { sdkVersion.set(35) }
   ```

   Use when you can't bump the JDK; previews render against the SDK 35 framework. Note that
   if your APK's manifest claims `minSdk ≥ 36`, PackageParser will still refuse (row 5 above)
   — only JDK 21 + auto-detect resolves that combination.

3. **Wait for Robolectric to ship API 37+** if you're already on `compileSdk = 37` and want
   the rendered framework to match exactly. The matrix's `4.17-SNAPSHOT` cells track upstream
   progress on this; once a release lands, drop the snapshot cells, bump `libs.robolectric` in
   `gradle/libs.versions.toml`, and update this page. Consumers who want to ride the snapshot
   today can force the version themselves:

   ```kotlin
   // settings.gradle.kts — add the snapshots repo:
   dependencyResolutionManagement {
     repositories {
       maven("https://oss.sonatype.org/content/repositories/snapshots/") {
         content { includeGroup("org.robolectric") }
       }
     }
   }
   // module's build.gradle.kts — force the snapshot:
   configurations.all {
     resolutionStrategy.force("org.robolectric:robolectric:4.17-SNAPSHOT")
   }
   ```

   This is exactly what the matrix's snapshot cells do via the
   `composeai.matrix.robolectricVersion` property.

## Updating this doc

When the matrix expectations shift (e.g. Robolectric adds API 37, or the project's toolchain
moves to JDK 21), update both:

1. The `expect:` field in [`.github/workflows/sdk-matrix.yml`](../.github/workflows/sdk-matrix.yml)
   (per-cell).
2. The "Expectation table" above so the doc stays the contract.

The nightly job's aggregator step fails outright if any cell's outcome drifts from the
`expect:` field — that's the signal to revisit this page.
