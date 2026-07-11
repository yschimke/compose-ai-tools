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
ARG CP_VERSION=0.16.37
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
# Stage 2: runtime.
# ---------------------------------------------------------------------------
FROM base AS runtime
ARG CP_VERSION
COPY --from=build "/opt/compose-preview-${CP_VERSION}" "/opt/compose-preview-${CP_VERSION}"
RUN ln -s "/opt/compose-preview-${CP_VERSION}" /opt/compose-preview
ENV PATH="/opt/compose-preview/bin:${PATH}" \
    SERVE_IDLE_EXIT=0 \
    SERVE_TIMEOUT=1800 \
    COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL=1 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"

WORKDIR /project
# Carry the project + warmed Gradle cache + module build outputs so the first
# runtime render is incremental, not a cold rebuild. Carry Maven local too so a
# cold runtime re-resolve still finds the plugin without reaching Central.
COPY --from=build /project /project
COPY --from=build /root/.gradle /root/.gradle
COPY --from=build /root/.m2 /root/.m2

# Bake the branch-trust store so a public deploy badges the published
# design-artifacts catalogs as Trusted(Branch) out of the box (SERVE_TRUST_STORE
# defaults to /trust/producers.json). Override by mounting your own there.
COPY trust/ /trust/

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
