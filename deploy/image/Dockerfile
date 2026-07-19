# syntax=docker/dockerfile:1.25

# Fast, prebuilt-friendly image for `compose-preview serve`.
#
# Unlike deploy/cloudrun/Dockerfile (which compiles the whole tool from source,
# ~8 min), this installs the RELEASED compose-preview CLI tarball and renders a
# tiny self-contained Compose Desktop project using the PUBLISHED plugin from
# Maven Central. No build-logic, no gradle-plugin, no ~50 data modules to compile
# — just a download + one warm render. Built once in CI and pushed to GHCR, so
# hosts pull it and never build at all.
# Bumped automatically by release-please on each CLI release (see release-please-config.json
# extra-files) so a fallback / local `docker build` never ships a stale CLI. An inline
# `# x-release-please-version` can't go on the ARG line (Docker folds it into the value), so use
# the block-marker form.
# x-release-please-start-version
ARG CP_VERSION=0.16.57
# x-release-please-end

# ---------------------------------------------------------------------------
# Stage 0: base — JDK + Skiko's native deps (same rationale as the cloudrun image:
# libGL via libgl1, Mesa software GLX/DRI, fonts; force software GL).
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17.0.19_10-jdk AS base
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      libfreetype6 libfontconfig1 fontconfig fonts-dejavu-core \
      libgl1 libglx-mesa0 libgl1-mesa-dri \
      curl ca-certificates git \
 && rm -rf /var/lib/apt/lists/*
ENV LIBGL_ALWAYS_SOFTWARE=1 \
    GRADLE_USER_HOME=/root/.gradle

# ---------------------------------------------------------------------------
# Stage 1: fetch the released CLI and warm the render path against the project.
# ---------------------------------------------------------------------------
FROM base AS build
ARG CP_VERSION
WORKDIR /opt
# Download + unpack the released distribution (bin/compose-preview + lib dirs).
# --retry-all-errors + a long window waits out the release-asset upload race: on a
# fresh `v*` release the GitHub Release exists before release.yml finishes pushing
# the tarball, so the first GETs can 404. Retry every 30s for up to ~10 min.
RUN curl -fsSL --retry 20 --retry-delay 30 --retry-all-errors -o /tmp/cp.tgz \
      "https://github.com/yschimke/compose-ai-tools/releases/download/v${CP_VERSION}/compose-preview-${CP_VERSION}.tar.gz" \
 && tar -xzf /tmp/cp.tgz -C /opt \
 && rm /tmp/cp.tgz \
 && ln -s "/opt/compose-preview-${CP_VERSION}" /opt/compose-preview
ENV PATH="/opt/compose-preview/bin:${PATH}"

WORKDIR /project
COPY sample-project/ /project/

# Seed Maven local from the same release run (preview-host-image.yml downloads the
# `preview-host-maven` artifact published by release.yml into deploy/image/m2/).
# With COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL=1 the CLI's auto-inject seeds
# mavenLocal() additively, so the warm render resolves the plugin + runtime modules
# from these local jars and never has to wait on Maven Central propagation. The dir
# always exists (.keep); on the manual workflow_dispatch path it's empty and the
# render falls back to Central via the retry loop below.
COPY m2/ /root/.m2/repository/
ENV COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL=1

# Warm render: builds the module via the project's Gradle wrapper, pulling the
# published plugin + Compose from Maven, then renders every preview once. Populates
# ~/.gradle + /project/build so the runtime container starts warm. A long --timeout
# keeps slow builders from tripping the CLI's 300s default.
#
# Retry the render: it's the actual Maven resolve (plugin marker + the
# implementation/`daemon-desktop`/renderer modules the plugin pulls), and those
# propagate to Central independently after a release — so even past the workflow's
# pre-build wait, one module can still be a minute behind. Retrying the real
# resolve covers every module without enumerating them; ~12 min of attempts rides
# out the lag, then it fails hard so a genuinely broken release doesn't ship.
RUN chmod +x ./gradlew \
 && ok=0; for i in $(seq 1 6); do \
      if compose-preview serve --export /tmp/warm-bundle --timeout 1800; then ok=1; break; fi; \
      echo "warm render failed (attempt ${i}/6) — likely Maven Central propagation; retrying in 120s"; \
      rm -rf /tmp/warm-bundle; sleep 120; \
    done; \
    [ "${ok}" = 1 ] || { echo "warm render failed after retries" >&2; exit 1; }; \
    rm -rf /tmp/warm-bundle

# ---------------------------------------------------------------------------
# Stage 1b (optional): Android/Wear live daemon — "do the slow work upfront".
#
# An Android/Wear catalog (wear-m3) renders live through a Robolectric daemon, NOT
# the desktop Skiko one. That runtime ships SEPARATELY (it's ~150-200 MB, too big
# for the CLI tarball) as `compose-preview-android-daemon-<ver>.zip`, and needs
# `android.jar` from an Android SDK plus, on its FIRST render, Robolectric's
# `android-all-instrumented` jar (~150 MB). All three are baked here so the runtime
# never pays a download on the request path — combined with serve's startup
# `prewarm()` (warmInBackground), the first live Wear browse is already warm.
#
# The runtime stage always carries these (this image fronts the Wear catalog); they
# stay inert unless a served catalog actually resolves an android `liveBundle`, in
# which case ServeBundleDaemon.materialize picks up the sidecar + android.jar here.
FROM base AS android-daemon
ARG CP_VERSION
ARG ANDROID_SDK_PLATFORM=android-35
# Minimal Android SDK: just the cmdline-tools + one platform's android.jar (no
# emulator/build-tools — Robolectric renders host-side).
ENV ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
RUN set -eu; \
    apt-get update && apt-get install -y --no-install-recommends unzip; \
    rm -rf /var/lib/apt/lists/*; \
    mkdir -p "${ANDROID_HOME}/cmdline-tools"; \
    curl -fsSL -o /tmp/cmdtools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"; \
    unzip -q /tmp/cmdtools.zip -d "${ANDROID_HOME}/cmdline-tools"; \
    mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest"; \
    rm /tmp/cmdtools.zip; \
    yes | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null; \
    "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" "platforms;${ANDROID_SDK_PLATFORM}" >/dev/null
# Unpack the released Android daemon sidecar → /opt/lib-daemon-android (same retry
# window as the CLI tarball, for the release-asset upload race).
RUN set -eu; \
    curl -fsSL --retry 20 --retry-delay 30 --retry-all-errors -o /tmp/android-daemon.zip \
      "https://github.com/yschimke/compose-ai-tools/releases/download/v${CP_VERSION}/compose-preview-android-daemon-${CP_VERSION}.zip"; \
    unzip -q /tmp/android-daemon.zip -d /opt; \
    rm /tmp/android-daemon.zip; \
    test -d /opt/lib-daemon-android
# Pre-fetch Robolectric's instrumented `android-all` runtime (~200 MB) into the local
# Maven cache so the daemon's FIRST render doesn't download it (the runtime stage
# carries this m2 subtree). This removes the download from the cold start — though the
# dominant cold cost is the per-process Robolectric/Compose warm-up + first render,
# which only serve's prewarm can move off the request path (a cached android-all still
# leaves that). The coordinate is Robolectric-version + SDK specific (4.16.1 → SDK 35),
# so it's an ARG: bump it when the bundled robolectric or ANDROID_SDK_PLATFORM changes
# (it's the `android-all-instrumented-<ver>` dir that appears under ~/.m2 after a first
# render). Best-effort — the daemon still fetches at runtime if this is stale/missing.
ARG ANDROID_ALL_VERSION=15-robolectric-13954326-i7
# Download to a temp path and move on success. A partial jar left at the real
# `jarPath()` would be worse than no prefetch: Robolectric 4.16.1's MavenArtifactFetcher
# skips its runtime download when the target exists, so a truncated file would boot the
# daemon with a corrupt android-all instead of falling back. On any failure, delete the
# temp file and let the daemon fetch at runtime.
RUN set -eu; \
    d="/root/.m2/repository/org/robolectric/android-all-instrumented/${ANDROID_ALL_VERSION}"; \
    f="$d/android-all-instrumented-${ANDROID_ALL_VERSION}.jar"; \
    mkdir -p "$d"; \
    if curl -fsSL --retry 10 --retry-delay 15 --retry-all-errors -o "$f.tmp" \
        "https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented/${ANDROID_ALL_VERSION}/android-all-instrumented-${ANDROID_ALL_VERSION}.jar"; then \
      mv "$f.tmp" "$f"; \
    else \
      rm -f "$f.tmp"; \
      echo "warn: android-all prefetch failed (coordinate stale?); daemon will fetch at runtime"; \
    fi

# ---------------------------------------------------------------------------
# Stage 2: runtime.
# ---------------------------------------------------------------------------
FROM base AS runtime
ARG CP_VERSION
COPY --from=build "/opt/compose-preview-${CP_VERSION}" "/opt/compose-preview-${CP_VERSION}"
RUN ln -s "/opt/compose-preview-${CP_VERSION}" /opt/compose-preview
# Android/Wear live-render bits (see the android-daemon stage): the Robolectric
# daemon sidecar + a minimal Android SDK (android.jar). Carried so a served Wear
# catalog with an android liveBundle renders live per-variant.
COPY --from=android-daemon /opt/lib-daemon-android /opt/lib-daemon-android
COPY --from=android-daemon /opt/android-sdk /opt/android-sdk
# JAVA_TOOL_OPTIONS (inherited by the spawned daemon JVM too) points the CLI at the
# unpacked Android daemon sidecar, warms the daemon off the request path
# (warmInBackground → serve's prewarm), and lifts BOTH cold-start budgets so a cold
# Robolectric daemon doesn't abort before it can serve live:
#   * sandboxBootTimeoutMs — the Robolectric sandbox boot (the one-time
#     `android-all-instrumented` ~150 MB fetch + instrumentation, in RobolectricHost.start,
#     default 600s) — the dominant cold cost; raised so a first boot completes.
#   * renderTimeoutSeconds — serve's per-render *cold* budget once booted.
#   * frameRenderTimeoutSeconds — serve's per-render budget for a *warm* frame. The 10s default
#     suits a warm Skiko desktop frame but starves a warm Robolectric (Wear) frame and any
#     override-bearing re-render (a `?knob.…=` edit forces a real recompose) on this shared box,
#     which surfaced as `/render?knob.…` returning `500 timed out after 10s`. Lift it in lockstep
#     with the cold budget so warm overrides/Wear frames have room (ServeRenderHost also gives the
#     first override render the cold budget, so this only bounds steady-state frames).
# Both are absorbed by prewarm off the request path, so a browse never blocks. See
# RobolectricHost / ServeCatalogLiveHost / ServeRenderHost.
#
# sandboxCount — the Android/Robolectric daemon defaults to a **5**-sandbox pool (DaemonMain,
# warmSpare). Five instrumented sandboxes (each ~150 MB+ of instrumented android-all in its own
# classloader) is heavy for this 8 GB box; the memory pressure is what makes one sandbox
# intermittently die with `Unable to load Robolectric native runtime library` mid-boot. Pin the
# pooled Android daemons to **3** (interactive needs ≥2; 3 keeps an interactive slot + two render
# slots) to cut that footprint ~40%. RobolectricHost also now retries a transient per-sandbox boot
# failure, so the two changes together keep the Wear live lane up instead of collapsing it to the
# baked-PNG snapshot. Desktop (CMP) daemons ignore this knob.
ENV PATH="/opt/compose-preview/bin:${PATH}" \
    SERVE_IDLE_EXIT=0 \
    SERVE_TIMEOUT=1800 \
    COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL=1 \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Dcomposeai.cli.libDaemonAndroidDir=/opt/lib-daemon-android -Dcomposeai.serve.warmInBackground=true -Dcomposeai.serve.renderTimeoutSeconds=900 -Dcomposeai.serve.frameRenderTimeoutSeconds=120 -Dcomposeai.daemon.sandboxCount=3 -Dcomposeai.daemon.sandboxBootTimeoutMs=1800000"

WORKDIR /project
# Carry the project + warmed Gradle cache + module build outputs so the first
# runtime render is incremental, not a cold rebuild. Carry Maven local too so a
# cold runtime re-resolve still finds the plugin without reaching Central.
COPY --from=build /project /project
COPY --from=build /root/.gradle /root/.gradle
COPY --from=build /root/.m2 /root/.m2
# Merge the pre-fetched Robolectric `android-all` runtime into the Maven cache (after
# the build-stage m2 so it lands alongside it), so the Android daemon's first render
# skips the ~200 MB download.
COPY --from=android-daemon /root/.m2/repository/org/robolectric /root/.m2/repository/org/robolectric

# Bake the branch-trust store so a public deploy badges the published
# design-artifacts catalogs as Trusted(Branch) out of the box (SERVE_TRUST_STORE
# defaults to /trust/producers.json). Override by mounting your own there.
COPY trust/ /trust/

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
