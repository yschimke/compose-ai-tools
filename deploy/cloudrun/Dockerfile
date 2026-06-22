# syntax=docker/dockerfile:1.24

# Cloud Run image for `compose-preview serve` (public mode, Desktop-only).
#
# What this serves: a long-lived HTTP server that renders the @Preview functions
# of one Compose Multiplatform *Desktop* module (`:samples:cmp` by default) on
# demand and returns PNGs. Desktop rendering uses Skiko software rasterization —
# no Android SDK, no GPU, no X server.
#
# Why a fat image: `compose-preview serve` builds its module through the Gradle
# Tooling API at startup and keeps a warm render daemon. So the runtime needs the
# JDK, the repo, and a warmed Gradle cache. We pre-build at image-build time so the
# container's first render is incremental (warm) rather than a cold full build.
#
# The repo toolchain is pinned to JDK 17 (gradle/gradle-daemon-jvm.properties), so
# we base on Temurin 17 — Gradle's toolchain auto-detection then finds a matching
# JDK with none of the JDK-17-vs-21 gymnastics docs/CLOUD-TESTING.md describes.

# ---------------------------------------------------------------------------
# Stage 1: build the CLI distribution and warm the render path.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17.0.18_8-jdk AS build

ENV GRADLE_USER_HOME=/root/.gradle \
    SERVE_MODULE=:samples:cmp

WORKDIR /app

# Copy the whole repo. A live preview server needs the module under render plus
# its build-logic, gradle-plugin, and settings to resolve — there is no useful
# smaller subset. .dockerignore trims build outputs, .git, node_modules, etc.
COPY . /app

# Build the installable CLI dist (bin/compose-preview + lib/ + desktop sidecars),
# then exercise the full build → daemon → render path once via `--export`. That
# one render populates the Gradle dependency cache, downloads the Gradle
# distribution, and emits the module's daemon-launch.json, so the runtime
# container starts warm instead of paying a cold full build on first request.
# NOTE: deliberately no BuildKit cache mount here — the warmed Gradle cache must
# land in a real image layer so `COPY --from=build /root/.gradle` carries it into
# the runtime image (cache-mount contents are not persisted into layers).
RUN ./gradlew --no-daemon :cli:installDist \
 && ./cli/build/install/compose-preview/bin/compose-preview serve \
        --module "${SERVE_MODULE}" \
        --export /tmp/warm-bundle \
 && rm -rf /tmp/warm-bundle

# ---------------------------------------------------------------------------
# Stage 2: runtime.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17.0.18_8-jdk AS runtime

# Skiko (Compose Desktop's renderer) needs fontconfig + freetype to lay out and
# rasterize text, and at least one font family for non-empty glyphs. Without
# these, text in rendered previews comes out blank.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      libfreetype6 libfontconfig1 fontconfig fonts-dejavu-core \
 && rm -rf /var/lib/apt/lists/*

ENV GRADLE_USER_HOME=/root/.gradle \
    SERVE_MODULE=:samples:cmp \
    # Scale-to-zero friendliness: shut the server down after this many idle
    # seconds so Cloud Run's instance can be reclaimed. Tune via env / service.yaml.
    SERVE_IDLE_EXIT=900 \
    # JVM heap ceiling for the CLI process; the render daemon is a child JVM.
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"

WORKDIR /app

# Carry over the repo, the built CLI dist, and — crucially — the warmed Gradle
# cache and module build outputs from the build stage.
COPY --from=build /app /app
COPY --from=build /root/.gradle /root/.gradle

COPY deploy/cloudrun/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

# Documentation only — Cloud Run injects the real port via $PORT (default 8080).
EXPOSE 8080

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
