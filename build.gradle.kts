plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.ktfmt) apply false
}

allprojects {
  apply(plugin = "com.ncorti.ktfmt.gradle")
  extensions.configure<com.ncorti.ktfmt.gradle.KtfmtExtension>("ktfmt") { googleStyle() }
}

// `./gradlew ktfmtCheck` already fans out to every project that applies the
// plugin via Gradle's task-name matching. The aggregate tasks below add the
// `gradle-plugin` included build, whose tasks aren't reachable that way.
fun Project.taskPath(name: String) = if (path == ":") ":$name" else "$path:$name"

tasks.register("ktfmtCheckAll") {
  group = "verification"
  description = "Runs ktfmtCheck across this build and the gradle-plugin included build."
  dependsOn(gradle.includedBuild("gradle-plugin").task(":ktfmtCheck"))
  allprojects.forEach { dependsOn(it.taskPath("ktfmtCheck")) }
}

tasks.register("ktfmtFormatAll") {
  group = "formatting"
  description = "Runs ktfmtFormat across this build and the gradle-plugin included build."
  dependsOn(gradle.includedBuild("gradle-plugin").task(":ktfmtFormat"))
  allprojects.forEach { dependsOn(it.taskPath("ktfmtFormat")) }
}
