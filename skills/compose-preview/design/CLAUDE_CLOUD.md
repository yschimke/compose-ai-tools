# compose-ai-tools in Claude Code cloud environments

Notes for running this project's tooling inside Claude Code's cloud sandbox
(claude.ai/code / "Claude on the web"). The default network level handles
CMP Desktop / pure-JVM; Android-consumer builds need one extra step.

## TL;DR

1. **Network level → Custom** with these four hosts allowlisted (keep
   "include Trusted defaults" on):

   | Host | Why |
   | --- | --- |
   | `maven.google.com` | AGP + AndroidX |
   | `dl.google.com` | Android SDK cmdline-tools / Google Maven mirror |
   | `fonts.googleapis.com` | Google Fonts API (downloadable fonts) |
   | `fonts.gstatic.com` | Google Fonts static assets |

   CMP Desktop / pure-JVM consumers can stay on **Trusted** — the four hosts
   are Android + downloadable-fonts specific. Don't use **Full**; it's broader
   than needed.

2. **Drop the install script into the environment setup script:**

   ```
   curl -fsSL https://raw.githubusercontent.com/yschimke/compose-ai-tools/main/scripts/install.sh | bash
   ```

3. **Verify** in a fresh session: `compose-preview doctor`. With the cloud
   sandbox detected (via `$CLAUDE_ENV_FILE` / `$CLAUDE_CODE_SESSION_ID`),
   doctor emits an `env.claude-cloud` info check, probes the four hosts
   above, and tailors remediation to the Claude Code UI ("Trusted → Custom,
   add the missing host") rather than generic proxy text.

## Cloud sandbox network levels

Per the [Claude Code on the web docs](https://code.claude.com/docs/en/claude-code-on-the-web#network-access):

| Level | What it is | When to pick it for this project |
| --- | --- | --- |
| **None** | No outbound traffic | Reading rendered PNGs only — no `compose-preview list/show/render`. |
| **Trusted** *(default)* | Allowlisted package registries: `gradle.org`, `services.gradle.org`, `plugins.gradle.org`, `repo.maven.apache.org`, `repo1.maven.org`, Maven Central mirrors, plus Spring/JCenter/etc. | CMP Desktop builds, the `compose-preview` CLI itself, anything that resolves only from Maven Central + the Gradle Plugin Portal. |
| **Custom** | Your own allowlist. Toggle "include Trusted defaults" to keep Maven Central etc. on top. | **Android builds** — add `dl.google.com` + `maven.google.com` (AGP/AndroidX). Internal Nexus/Artifactory goes here too. |
| **Full** | Any domain | Only if you don't yet know what you'll hit; over-broad for this project. |

What's **not** on the Trusted defaults and matters here:

- `dl.google.com`, `maven.google.com` — AGP and AndroidX. Required for
  anything that applies `com.android.application` / `com.android.library`,
  including this repo from source (the root `build.gradle.kts` declares the
  AGP plugin even with `apply false`, so resolution happens at config time).
- `repo.gradle.org/gradle/libs-releases` — hosts `gradle-tooling-api`,
  which the `:cli` module depends on. Blocked on Trusted, so building the
  CLI *from source* requires Custom mode. Running the CLI from the release
  tarball doesn't hit this host; `./scripts/install.sh` covers the common
  case without needing it.
- `api.adoptium.net` — Gradle's JDK toolchain auto-provisioning. Ask Gradle
  to download a JDK and the build fails. See gotchas below.
- `api.github.com` — rate-limits unauthenticated calls from shared sandbox
  IPs. `scripts/install.sh` deliberately avoids it (uses `github.com` HTML
  redirects for version resolution and `releases/download/` for assets).

Pre-installed toolchain: **OpenJDK 21 only.** The CLI, plugin, and renderer
JARs target JDK 17 bytecode and run fine on 21, so `install.sh` reuses it
in the common case. Building this repo from source needs JDK 17 (the
Gradle daemon is pinned via `gradle/gradle-daemon-jvm.properties`);
`install.sh` apt-installs `openjdk-17-jdk-headless` as a fallback.

## What `install.sh` produces

```
~/.claude/skills/compose-preview/
|-- SKILL.md                                       (from the skill tarball)
|-- design/...                                     (from the skill tarball)
|-- cli/compose-preview-<ver>/bin/compose-preview  (from the CLI tarball)
`-- bin/compose-preview -> ../cli/.../compose-preview
```

`~/.claude/skills/compose-preview/` is the path Claude Code's skill
discovery walks, so dropping the bundle there makes the skill available in
any subsequent session. `~/.local/bin/compose-preview` is also symlinked
for direct CLI use outside agent invocation.

In cloud mode the script also: appends `JAVA_HOME` and `PATH` to
`$CLAUDE_ENV_FILE` so subsequent tool invocations inherit them; translates
`$https_proxy` / `$http_proxy` into `JAVA_TOOL_OPTIONS`
(`-Dhttps.proxyHost` / `-Dhttp.proxyHost`) and writes that to the same env
file (the JVM's `HttpURLConnection` ignores shell proxy env vars — see
gotchas). Force with `CLAUDE_CLOUD=1` / disable with `CLAUDE_CLOUD=0`.
Idempotent on re-run.

## Trusted mode (CMP / pure-JVM)

Setup script for the **default Trusted** network level — works for CMP
Desktop / pure-JVM consumer projects. For Android consumers, use this same
recipe but switch to Custom first (see below).

```bash
#!/usr/bin/env bash
set -euo pipefail

curl -fsSL https://raw.githubusercontent.com/yschimke/compose-ai-tools/main/scripts/install.sh | bash

# Optional pre-warm — bakes Gradle's downloaded deps into the env snapshot.
# CLI auto-discovers every module that applies the plugin.
export PATH=$HOME/.local/bin:$PATH
compose-preview show --json --brief || true
```

Verify with `compose-preview doctor`. Expected: a `[env]` block showing
JDK 17+ on PATH (21 on current images), Gradle reachable, and four
`env.network.*` checks. On Trusted, the Google hosts will warn — that's
expected if you only render CMP Desktop / JVM. The `[project]` block
either lists per-module results or reports "no modules have the
compose-preview plugin applied".

The plugin resolves from Maven Central, so doctor doesn't probe GitHub
Packages. Drop any leftover `maven.pkg.github.com` from older
`settings.gradle[.kts]` snippets.

## Custom mode (Android consumers)

Switch the Claude Code web UI's network level to **Custom**, keep "include
Trusted defaults" on, and add:

- `dl.google.com` — Android SDK cmdline-tools / platform downloads, plus
  Google's fallback Maven mirror
- `maven.google.com` — AGP, AndroidX, Robolectric transitive deps
- `fonts.googleapis.com` + `fonts.gstatic.com` — only if you use
  `androidx.compose.ui:ui-text-google-fonts` at render time

Same install bootstrap with `--android-sdk` to also install the Android
SDK (cmdline-tools + `platforms;android-36` + `platform-tools` +
`build-tools;36.0.0`) into `/opt/android-sdk` and append `ANDROID_HOME`
to `$CLAUDE_ENV_FILE`:

```bash
curl -fsSL https://raw.githubusercontent.com/yschimke/compose-ai-tools/main/scripts/install.sh \
  | bash -s -- --android-sdk

# Optional pre-warm; `|| true` so a partial render still populates the cache.
./gradlew --no-daemon :samples:android:renderAllPreviews || true
```

Override the install location with `ANDROID_HOME=…` before the curl if
you want it somewhere other than `/opt/android-sdk` (e.g. `$HOME/android-sdk`
to avoid the sudo path). Idempotent — re-running with the SDK already
present at `$ANDROID_HOME/platforms/android-36` is a fast no-op.

## Two known gotchas

Both bite people setting Gradle up in the cloud sandbox for the first time.

### Java's `HttpURLConnection` ignores `https_proxy`

The cloud sandbox routes egress through a proxy and exports `https_proxy`,
but the JVM's built-in HTTP client ignores it. The Gradle wrapper hits this
during initial distribution download; downstream `connection.connect()`
calls in the Tooling API can hit it too
([anthropics/claude-code#16222](https://github.com/anthropics/claude-code/issues/16222)).

`scripts/install.sh` handles this: when run in cloud mode, it parses the
shell proxy URL and appends a matching
`JAVA_TOOL_OPTIONS="-Dhttps.proxyHost=… -Dhttps.proxyPort=… -Dhttp.proxyHost=… -Dhttp.proxyPort=…"`
line to `$CLAUDE_ENV_FILE`, which the sandbox picks up for every subsequent
tool invocation.

If you're not using `install.sh` (or you're debugging a different JVM tool
that hits the same issue), two manual workarounds:

- **Pre-download** the Gradle distribution in the setup script via `curl`
  (which honors `https_proxy`) and stash it under
  `~/.gradle/wrapper/dists/gradle-<ver>-bin/<hash>/` so the wrapper finds it
  locally.
- **Force the JVM through the proxy** by exporting
  `JAVA_TOOL_OPTIONS="-Dhttps.proxyHost=<host> -Dhttps.proxyPort=<port>"`
  yourself, or install
  [tschuehly/claude-code-gradle-proxy](https://github.com/tschuehly/claude-code-gradle-proxy),
  which does the same thing as a `PreToolUse` hook on `Bash`.

### Toolchain auto-provisioning is blocked

`api.adoptium.net` (and the other toolchain vendor APIs) aren't on the
Trusted allowlist. Any Gradle build that asks for a JDK it doesn't have —
e.g. a Java-25 toolchain in an env that ships JDK 21 — fails with
"Unable to download toolchain". Install the JDK in the setup script (see
above), or add the vendor APIs via Custom.

## Other caveats

- **Keep rendered previews under 1800px on the longest edge.** Claude's
  cloud session enters a bad state when it's asked to view an image larger
  than that, and recovery usually means restarting the session. Previews
  above the threshold — long scroll captures, stitched `ScrollMode.LONG`
  outputs, high-DPI fan-outs — should be rendered at a smaller size (shrink
  `widthDp`/`heightDp` or the density) or down-scaled before an agent
  reads them. `compose-preview show --brief` only returns paths, so you
  won't trip the limit until something actually loads the PNG.

## Primary sources

- [Claude Code on the web — network access](https://code.claude.com/docs/en/claude-code-on-the-web#network-access)
- [Sandboxing](https://code.claude.com/docs/en/sandboxing.md)
- [Hooks reference — SessionStart](https://code.claude.com/docs/en/hooks.md#sessionstart)
- [anthropics/claude-code#16222 — Gradle wrapper + Java proxy](https://github.com/anthropics/claude-code/issues/16222)
- [tschuehly/claude-code-gradle-proxy](https://github.com/tschuehly/claude-code-gradle-proxy) — `JAVA_TOOL_OPTIONS` workaround
