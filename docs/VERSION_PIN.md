# The project version pin

**One value in your repo names the compose-preview version, and every entrypoint honours it.**

Issue [#3738](https://github.com/yschimke/compose-ai-tools/issues/3738).

## The problem it solves

compose-preview reaches a project through several doors, and until the pin each one
picked a version by itself:

| Entrypoint | Version it used |
|---|---|
| `compose-preview` CLI | whichever release the developer installed; auto-injected the plugin at that version |
| VS Code extension | the version compiled into the installed VSIX |
| `install` / `apply` composite actions | the `version:` input — `latest` by default |
| Manually applied Gradle plugin | whatever `build.gradle.kts` or the version catalog pins |

So a single project could render three different ways: a developer's CLI on the
newest release, a teammate's editor on an older VSIX, and CI floating on `latest`.
When those drift across a release the failure is not obvious — a newer CLI's Tooling
model query doesn't discover an older plugin, and the render reports "no modules have
the compose-preview plugin applied", which reads like a project misconfiguration
rather than the version skew it is. That's the failure
[#1920](https://github.com/yschimke/compose-ai-tools/issues/1920) documented from the
CI side; the pin generalises the fix to every door.

## Setting a pin

```sh
compose-preview pin --cli        # pin to the CLI you're running
compose-preview pin 1.1.0        # pin to a specific release
compose-preview pin              # show the current pin and whether the CLI matches
compose-preview pin --remove     # unpin
```

`pin <version>` writes one line into your project's `gradle.properties`:

```properties
# compose-preview version pin — read by the CLI, the VS Code extension and the
# install / apply GitHub actions so every entrypoint uses the same release.
# Set with `compose-preview pin <version>`; remove with `compose-preview pin --remove`.
composePreview.version=1.1.0
```

That's the whole mechanism — no new file, no new config format, and the key lives in
the same `composePreview.*` Gradle-property namespace as the plugin's other knobs.

`compose-preview pin --json` prints the same report for agents and CI steps:

```json
{
  "pinned": true,
  "version": "1.1.0",
  "source": "gradle.properties (composePreview.version)",
  "cliVersion": "1.1.0",
  "matchesCli": true
}
```

## Sources and precedence

Every entrypoint resolves the pin the same way, first match wins:

1. **`--plugin-version <v>`** on a CLI invocation — a per-run override, nothing read
   from disk. (CLI only; the extension and the actions have no equivalent.)
2. **`COMPOSE_PREVIEW_VERSION`** in the environment — the CI / container override.
   (Not read by the composite actions, where the `version:` input *is* this knob.)
3. **`gradle.properties` → `composePreview.version`** — the canonical pin, and what
   `compose-preview pin` writes.
4. **`gradle/libs.versions.toml` → `[versions] composePreviewCli`** — the pre-existing,
   Renovate-friendly convention the `install` / `apply` actions already read via
   `version: catalog`. Kept as a source so projects already pinned that way get the
   CLI and the extension honouring it with no new file.

**No pin found → each entrypoint uses its own bundled version**, exactly as before the
pin existed. An unpinned project is a legitimate state, not a misconfiguration —
`doctor` reports it as `ok` with a nudge, never as a failure.

## What each entrypoint does with it

| Entrypoint | Behaviour |
|---|---|
| CLI | Auto-injects the **pinned** plugin version via `--init-script`, instead of its own `BUNDLE_VERSION`. Warns once per process when the pin and the running CLI disagree. |
| `compose-preview init-script` | Bakes the pinned version into the emitted script, so `./gradlew --init-script "$(compose-preview init-script --path)"` matches a plain `compose-preview render`. |
| `compose-preview doctor` | Reports `project.version-pin`: the pin, its source, and whether this CLI matches. `warning` on a mismatch between clean releases, `ok` otherwise. |
| VS Code extension | Auto-injects the pinned version and logs the pin + source at startup. |
| `install` action | `version: pin` resolves the project's pin. Fails when nothing is pinned, rather than silently falling back to `latest`. |
| `apply` action | `cli-version: auto` (the default) now checks the pin **first**, ahead of its existing catalog / build-script sniffing — so a pinned project is skew-proof in CI with no workflow change. |

## Scope: the pin governs auto-inject

The pin decides what **auto-inject** applies — the zero-config path where the CLI and the
extension inject the plugin via `--init-script`, which is how the large majority of
projects use compose-preview.

A module that declares the plugin itself — `id("ee.schimke.composeai.preview") version
"…"` or a catalog `alias(libs.plugins.…)` — keeps its own version, and the pin does not
touch it. That is not a gap: auto-inject already detects such a module and deliberately
skips injecting there (Gradle's `plugins {}` DSL rejects `id(…) version "…"` when the
same plugin is also on the buildscript classpath), so the declaration in the build script
is the only version in play. `compose-preview pin` never rewrites build scripts or
catalogs — a project that pins its plugin by hand already has a single source of truth,
and two tools editing the same declaration is worse than one.

`doctor` still reports both: `project.version-pin` (what the project pins) and
`project.plugin-version` (what is actually on the classpath), so a mixed project — some
modules declaring the plugin, others auto-injected — can see the two numbers side by side.

### The one thing a pin can't do

It cannot change the version of a binary that is already running. A CLI on `1.1.0`
driving a project pinned to `1.0.5` injects the **pinned** plugin — the pin is
authoritative, that is the point — but the daemon and renderer that CLI *ships* are
still `1.1.0`. Within a major that's harmless; across a major the render/daemon wire
format differs ([VERSIONING.md § 3](VERSIONING.md#3-what-counts-as-breaking)), so the
CLI says so:

```
compose-preview warning: this project pins compose-preview 2.0.0
(gradle.properties (composePreview.version)) but the CLI on $PATH is 1.1.0. Those are
different major versions — … Injecting the pinned plugin version. Align the CLI with
`compose-preview update 2.0.0`, or re-pin with `compose-preview pin 1.1.0`.
```

A `-SNAPSHOT` on either side is silent: driving a pinned project with a locally built
CLI is a deliberate development flow, not a misconfiguration.

## CI

```yaml
- uses: yschimke/compose-ai-tools/.github/actions/install@v1
  with:
    version: pin        # installs the version the project pins
```

`apply` needs no change — its default `cli-version: auto` picks the pin up on its own.

Renovate can bump the pin like any other version:

```json
{
  "customManagers": [
    {
      "customType": "regex",
      "managerFilePatterns": ["/^gradle\\.properties$/"],
      "matchStrings": ["composePreview\\.version\\s*=\\s*(?<currentValue>.+)"],
      "depNameTemplate": "yschimke/compose-ai-tools",
      "datasourceTemplate": "github-releases",
      "extractVersionTemplate": "^v(?<version>.*)$"
    }
  ]
}
```

## Implementation

Three implementations of one precedence list — a Kotlin binary, a VSIX, and composite
actions can't share code — each with tests that pin the same behaviour, so they can't
drift silently:

| Where | Resolver | Tests |
|---|---|---|
| CLI | [`cli/…/VersionPin.kt`](../cli/src/main/kotlin/ee/schimke/composeai/cli/VersionPin.kt) | [`VersionPinTest.kt`](../cli/src/test/kotlin/ee/schimke/composeai/cli/VersionPinTest.kt) |
| VS Code extension | [`vscode-extension/src/versionPin.ts`](../vscode-extension/src/versionPin.ts) | [`versionPin.test.ts`](../vscode-extension/src/test/versionPin.test.ts) |
| `install` action | [`resolve-version.py`](../.github/actions/install/resolve-version.py) | [`test_resolve_version.py`](../.github/actions/install/test_resolve_version.py) |
| `apply` action | [`check-skew.py`](../.github/actions/apply/check-skew.py) (`pin_from_properties`) | [`test_check_skew.py`](../.github/actions/apply/test_check_skew.py) |

**Changing the precedence list means changing all four.** The property name, the
environment variable, the catalog path and the catalog key are named constants in each
implementation for exactly that reason.

Writes only ever touch `gradle.properties`. A pin expressed in a version catalog is
*read* as a pin but never rewritten: catalogs are Renovate-managed, and editing one
behind the bot's back is how a pin and its update automation start fighting.
