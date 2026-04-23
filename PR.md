# PR Description - Add Diagnostic Warning for `ANDROID_JAR`

## Title
feat: add diagnostic warning for `ANDROID_JAR` environment variable

## Description
This PR adds a diagnostic warning to the `DiscoverPreviewsTask` to alert users when the `ANDROID_JAR` environment variable is set while running on Java 9 or higher.

### Background
During investigation of issue #142 and subsequent testing, it was discovered that having `ANDROID_JAR` set in the environment can cause Gradle's plain `Test` tasks (like `renderPreviews`) to automatically append the Android SDK jars to the `-Xbootclasspath` JVM argument on some platforms (specifically observed on Linux with specific JDKs). Since `-Xbootclasspath` is no longer supported in Java 9+, this causes the test worker JVM to fail to start with a cryptic error: `Could not create the Java Virtual Machine. -Xbootclasspath is no longer a supported option.`

### Changes
- Added a check in `DiscoverPreviewsTask.discover()` to inspect the `ANDROID_JAR` environment variable.
- If set and running on Java 9+, it logs a warning suggesting to unset it if failures occur.
- This warning helps users identify the root cause of the startup failure quickly.

### Verification Results
- Verified that unsetting `ANDROID_JAR` allows the tests to run successfully on the affected environment.
- Verified that the warning appears when the variable is set.
