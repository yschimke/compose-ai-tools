# Classloader forensics

The classloader forensic tools compare what the standalone Robolectric test path and daemon
Robolectric path load. Use them when Android preview rendering fails in a way that looks like
classloader identity skew, stale bytecode, or unexpected Robolectric instrumentation.

## Entry Points

- Library: `daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/forensics/ClassloaderForensics.kt`
- Standalone control test: `renderer-android/src/test/.../ClassloaderForensicsTest.kt`
- Daemon-path test: `daemon/android/src/test/.../ClassloaderForensicsDaemonTest.kt`
- Diff task: `./gradlew :daemon:harness:diffClassloaderForensics`

The diff task writes generated reports under
`daemon/harness/build/reports/classloader-forensics/diff.{md,json}` for local review. Those
generated reports are intentionally not checked in.

## What It Captures

For each surveyed class, the dump records:

- Fully qualified class name.
- Classloader type, identity hash, name, and parent chain.
- Code source location and package version metadata.
- Robolectric instrumentation hints.
- Optional class byte hash when available.

It also captures Robolectric runtime context: API level, qualifiers, graphics mode, looper mode,
instrumentation filters, sandbox classloader identity, Java version, and OS.

## Survey Set

Keep the survey focused on classes that reveal classloader skew:

- Compose runtime and UI classes such as `Composer`, `Composition`, `LocalContext`, and
  `ComposableMethod`.
- Compose UI test rule types.
- Roborazzi capture entry points.
- Robolectric runner, sandbox, shadow, and framework types.
- Android framework and `ComponentActivity` types.
- Daemon bridge, host, render engine, sandbox runner, and user classloader holder.
- The preview class involved in the failing scenario.

## How To Use The Diff

1. Run the standalone and daemon forensic tests, or run the harness diff task.
2. Compare loader identity and code source for Compose, Robolectric, AndroidX, and the preview class.
3. Treat these as high-signal failures:
   - Same class name loaded by different unexpected parents.
   - Daemon path loading a user preview through the sandbox parent instead of the disposable child.
   - Compose runtime or UI classes coming from different artifacts across paths.
   - Robolectric instrumentation present in the control path but absent in the daemon path.

Generated diffs are diagnostic artifacts. Do not rely on them as stable documentation.
