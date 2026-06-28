# syntax=docker/dockerfile:1.24

# Cloud Run image for `compose-preview serve` (public mode, Desktop-only).
#
# What this serves: a long-lived HTTP server that renders the @Preview functions
# of one Compose Multiplatform *Desktop* module (`:samples:cmp` by default) on
# demand and returns PNGs. Desktop rendering uses Skiko on a CPU raster surface —
# no GPU and no X server/Xvfb, but Skiko's native lib is still dynamically linked
# against libGL.so.1, so Mesa's software GL must be present (see the base stage).
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
# Stage 0: base — JDK plus the native libraries Skiko needs to render.
# ---------------------------------------------------------------------------
# Both the build-stage warm render and the runtime server invoke Skiko, so the
# native deps must live in BOTH — hence a shared base. Skiko dlopens libskiko,
# which links libGL.so.1 (owned by libgl1 — install it explicitly; the mesa
# packages don't pull it on a minimal base) and libX11 (pulled in by
# libglx-mesa0, which with libgl1-mesa-dri provides Mesa's software GLX/DRI);
# fontconfig + freetype + a font family give non-empty text. We
# rasterize on the CPU, so LIBGL_ALWAYS_SOFTWARE=1 forces Mesa's software path
# (no GPU on the host). This mirrors what the repo's own CI installs for headless
# desktop renders (.github/workflows/ci.yml).
FROM eclipse-temurin:17.0.18_8-jdk AS base
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      libfreetype6 libfontconfig1 fontconfig fonts-dejavu-core \
      libgl1 libglx-mesa0 libgl1-mesa-dri \
 && rm -rf /var/lib/apt/lists/*
ENV LIBGL_ALWAYS_SOFTWARE=1 \
    GRADLE_USER_HOME=/root/.gradle \
    SERVE_MODULE=:samples:cmp

# ---------------------------------------------------------------------------
# Stage 1: build the CLI distribution and warm the render path.
# ---------------------------------------------------------------------------
FROM base AS build

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
FROM base AS runtime

ENV SERVE_IDLE_EXIT=900 \
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
