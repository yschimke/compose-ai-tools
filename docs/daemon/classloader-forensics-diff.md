# Classloader forensics diff: standalone-control vs daemon-subject

- A (control): `/home/yuri/workspace/compose-ai-tools/renderer-android/build/reports/classloader-forensics/standalone.json`
- B (subject): `/home/yuri/workspace/compose-ai-tools/daemon/android/build/reports/classloader-forensics/daemon.json`
- Survey size: A=32, B=38, both=31
- Unchanged: 13/31

## 1. Smoking gun — different loader type / codeSource / bytecode

_None._ A and B agree on loader type, code source, and bytecode for every shared class. (Identity-hash-only differences within the same loader type are routed to § 7 as expected per-JVM-fork noise, not flagged here.)

## 2. Robolectric instrumentation flag mismatches

_None._ Every shared class has the same `robolectricInstrumented` flag in A and B.

## 3. Classes only in A

- `ee.schimke.composeai.renderer.ClassloaderForensicsFixturesKt`

## 4. Classes only in B

- `ee.schimke.composeai.daemon.RedFixturePreviewsKt`
- `ee.schimke.composeai.daemon.RenderEngine`
- `ee.schimke.composeai.daemon.RobolectricHost`
- `ee.schimke.composeai.daemon.SandboxHoldingRunner`
- `ee.schimke.composeai.daemon.UserClassLoaderHolder`
- `ee.schimke.composeai.daemon.bridge.DaemonHostBridge`
- `java.net.URLClassLoader`

## 5. Robolectric runtime-config diffs

_None._ Both runs report the same Robolectric config snapshot.

## 6. Other changes (package-version drift, etc.)

_None._

## 7. Identity-hash noise (expected per-JVM-fork variance)

18 classes load via the same loader type and bytecode in both configurations but with different `identityHashCode`s. Expected when A and B run in separate Gradle test forks — each Robolectric `Sandbox` builds a fresh `SandboxClassLoader` whose identity hash is per-JVM-run randomness. **Not a bug** unless A and B were supposed to share a JVM.

