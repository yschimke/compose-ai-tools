# Offline runnable bundle producer

A build-neutral library and small JVM distribution for discovery and PNG/ZIP
bundle preparation. It uses compiled project/runtime JARs, optional source files
and rules_jvm_external Maven lockfiles. It does not build the project, resolve
coordinates over the network, provision renderer sidecars, or render images.

Build with `scripts/agent-gradle.sh :bundle-producer:installDist :bundle-producer:distTar`.
The executable takes execution-root-relative metadata paths using the existing
artifacts contract: schema 1 for a JVM target, or schema 2, whose `android` block (the linked
resource APK, manifest and final-ID R jar an Android aspect wrote) makes a `backend="android"`
bundle carrying them:

```
compose-preview-producer --artifacts app.artifacts.json --output app.bundle.png \
  --maven-install maven_install.json --filter Greeting
```

The cover is a valid one-pixel PNG; the ZIP contains discovery, application
classes/resources, coordinates for identifiable published dependencies and
embedded unpublished dependencies. Source paths are normalized for portability.
Declared input changes invalidate preparation; unchanged inputs produce identical
bytes. Consumers render through `compose-preview bundle render app.bundle.png`.

After rendering to `<preview-id>.png` files, bake them into a separate product:

```
compose-preview-producer --bundle app.bundle.png --renders rendered --output baked.png
```

Baking requires all selected captures and preserves runtime metadata. The library
also supports packaging captures already produced by a CLI/other build adapter.

The `bundle-format` dependency excludes its optional Android daemon-client helper
runtime. The producer's dependency gate rejects daemon client/core/BTA, Kotlin
build-tools API, renderer/native and HTTP dependencies. Shared DTO/device catalog
artifacts remain; their legacy `daemon-*` names do not imply a running daemon.
Source-only consumers need the independent producer distribution, not this repo's
full build or CLI distribution. They can omit the native rule's `cli` attribute.

Reproducibility is verified with byte-identical declared runtime inputs and the
same pinned producer/JDK: sorted ZIP entries, fixed DOS timestamps independent
of timezone, UTF-8 encoding, canonical JSON object keys and stable preview IDs.
Runtime classpath and capture array order remain semantic and are preserved.
Baking is also byte reproducible given the same prepared bundle and PNG bytes;
image generation itself still depends on the renderer/native/font environment.
The external-consumer test copies all inputs to two different roots and executes
the producer under different JVM locales/timezones, comparing complete SHA-256s
with the original Bazel artifact, rather than relying on a cache hit.
