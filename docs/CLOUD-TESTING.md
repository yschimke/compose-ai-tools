# Testing the VS Code extension in a cloud sandbox

How to exercise the VS Code extension — including the full Confetti
external-consumer e2e — inside a locked-down cloud environment such as
[Claude Code on the web](https://code.claude.com/docs/en/claude-code-on-the-web)
running the **custom** (allowlist) network policy. Everything here is
about working around what that policy blocks; on a runner with open
egress (the GitHub Actions setup in
[`vscode-extension-e2e-external.yml`](../.github/workflows/vscode-extension-e2e-external.yml)),
none of this is necessary.

## TL;DR

```bash
vscode-extension/scripts/run-confetti-e2e.sh
```

That provisions JDK 17, the Android SDK, and VSCodium; publishes the
plugin to mavenLocal; clones + rewrites Confetti; and runs the e2e under
VSCodium + xvfb. Expect a green run to print:

```
[e2e-external] received 6 previews: …BackgroundKt.BackgroundDefault_Dark theme, …
1 passing
```

The non-VS-Code suites need none of the workarounds and run as documented
in [DEVELOPMENT.md](DEVELOPMENT.md):

```bash
cd vscode-extension && npm ci && npm test   # 1426 unit tests, pure node
```

## What the allowlist network policy blocks

Reachable: `github.com` + `*.githubusercontent.com`, `registry.npmjs.org`,
`services.gradle.org`, `repo.maven.apache.org`, `maven.google.com`,
`dl.google.com`, `jitpack.io`.

Blocked (HTTP 403): the whole Microsoft/VS Code host family —
`update.code.visualstudio.com`, `vscode.download.prss.microsoft.com`
(the VS Code binary), `marketplace.visualstudio.com`, `open-vsx.org`,
and the Playwright Chromium CDN. So three things break out of the box:

| Breakage | Symptom | Workaround |
|----------|---------|------------|
| `@vscode/test-electron` can't download VS Code | 403 from update host | VSCodium from GitHub + `VSCODE_TEST_EXECUTABLE` (see below) |
| `harness:snapshot` / `harness:contract` can't fetch Chromium | Playwright download 403 | not used in cloud; rely on `npm test` + the e2e |
| A freshly-installed Temurin JDK can't do HTTPS | `PKIX path building failed` on the first `./gradlew` fetch | give it the system trust store (see below) |

## The three setup fixes

### 1. VS Code binary → VSCodium

[`runTest.ts`](../vscode-extension/src/test/electron/runTest.ts) honours
`VSCODE_TEST_EXECUTABLE`: when set, it skips `downloadAndUnzipVSCode()`
and drives the given binary instead. Our extension and the fake
gradle/kotlin stubs are side-loaded via `extensionDevelopmentPath`, so the
marketplace is never consulted — VSCodium runs the electron suites
(`test:electron`, `test:e2e`, `test:e2e-external`) the same way stock VS
Code would.

```bash
export VSCODE_TEST_EXECUTABLE="$(vscode-extension/scripts/setup-vscodium.sh)"
```

Benign noise in the log: VSCodium probes its (blocked) extension gallery
and logs `queryRawGalleryExtensions Failed to fetch` plus a few
`ssl_client_socket … handshake failed` lines. Side-loaded extensions are
unaffected.

### 2 + 3. JDK 17, trust store, and the toolchain symlink

```bash
export JAVA_HOME="$(scripts/setup-cloud-jdk.sh)"
```

[`setup-cloud-jdk.sh`](../scripts/setup-cloud-jdk.sh) does three things,
each fixing a distinct cloud-specific failure:

- **Installs Temurin 17** from Adoptium's GitHub releases, because the
  container ships only JDK 21 but the build pins `toolchainVersion=17`
  ([`gradle/gradle-daemon-jvm.properties`](../gradle/gradle-daemon-jvm.properties)),
  and foojay auto-provisioning is blocked. (The bootstrap
  [`scripts/install.sh --android-sdk`](../scripts/install.sh) tries to
  apt-install JDK 17 and fails here — `security.ubuntu.com` 404s the
  pinned `.deb`; the Adoptium tarball is the reliable path.)
- **Copies the system trust store** (`/etc/ssl/certs/java/cacerts`, which
  carries the sandbox proxy's MITM CA) over Temurin's vanilla one.
  Without this, every Java-side HTTPS fetch — the Gradle distribution
  download, Maven Central / Google Maven dependency resolution — fails
  with `PKIX path building failed: unable to find valid certification
  path`. `curl` and the system JDK 21 work because their trust stores
  already carry the CA; a tarball Temurin does not.
- **Symlinks the JDK into `/usr/lib/jvm`** so Gradle's "Common Linux
  Locations" toolchain auto-detection finds it. This is the linchpin:
  Gradle otherwise only sees a JDK that is the *current* daemon JVM, so a
  build whose daemon runs on JDK 21 (because `JAVA_HOME` did not propagate
  to a spawned `gradlew`) can't resolve a `languageVersion=17` toolchain
  even with the JDK on disk. The symlink is what makes the repo's own
  `toolchainVersion=17` daemon run under a JDK-21 launcher, and what lets
  Confetti's `:proto` toolchain-17 module resolve while the daemon renders
  on JDK 21.

## JDK topology for the Confetti e2e

Confetti's `:androidApp` is `compileSdk = 36`, so the plugin pins
Robolectric SDK 36, which requires the **render JVM to be JDK 21+**
(`DefaultSdkProvider.verifySupportedSdk`). But `:proto` (and the repo's
own build) want a **JDK 17 toolchain**. Both are satisfied at once:

- **`publishToMavenLocal`** runs under **JDK 17** (the repo's pinned
  toolchain).
- **The e2e** runs with the **system JDK 21** as the daemon/render JVM
  (`unset JAVA_HOME`, `java` → `/usr/bin/java`), while the
  `/usr/lib/jvm/temurin-17` symlink keeps toolchain-17 modules resolvable.

A common failure mode while iterating: a **stale Gradle daemon** from a
previous run on the other JDK gets reused and poisons toolchain
detection, surfacing as
`Cannot find a Java installation … matching {languageVersion=17}`. Run
`(cd "$WORKSPACE" && ./gradlew --stop)` between runs — the orchestrator
does this for you.

## Manual run (what the orchestrator automates)

```bash
export JAVA_HOME="$(scripts/setup-cloud-jdk.sh)"
export VSCODE_TEST_EXECUTABLE="$(vscode-extension/scripts/setup-vscodium.sh)"
ANDROID_HOME=/opt/android-sdk scripts/install.sh --android-sdk     # SDK only; JDK already present

# Publish the plugin under JDK 17 so Confetti's catalog SNAPSHOT resolves.
JAVA_HOME="$JAVA_HOME" ./gradlew publishToMavenLocal --no-daemon
JAVA_HOME="$JAVA_HOME" ./gradlew -p gradle-plugin publishToMavenLocal --no-daemon

# Clone + rewrite Confetti to point at the SNAPSHOT.
WS="$(vscode-extension/scripts/setup-external-e2e.sh /tmp/compose-preview-external-e2e)"

# Run the e2e on the system JDK 21 (render) with JDK 17 available for toolchains.
cd vscode-extension
( cd "$WS" && ./gradlew --stop || true )
env -u JAVA_HOME PATH=/opt/node22/bin:/usr/bin:/bin \
  ANDROID_HOME=/opt/android-sdk \
  VSCODE_TEST_EXECUTABLE="$VSCODE_TEST_EXECUTABLE" \
  COMPOSE_PREVIEW_E2E_WORKSPACE="$WS" \
  xvfb-run -a npm run test:e2e-external
```
