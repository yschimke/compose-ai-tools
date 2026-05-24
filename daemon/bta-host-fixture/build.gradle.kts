// Stage-2 BTA spike — Gradle-parity fixture. Same Kotlin source `fixture/Greeting.kt`
// the BTA spike's own tests compile, but built through Gradle's standard `compileKotlin`
// task so `BtaCompilerGradleParityTest` (in `:daemon:bta-host`) has a reference artefact
// to diff BTA's output against. NOT published. NOT depended on by anything other than the
// bta-host test classpath. Remove together with `:daemon:bta-host` when the spike retires.

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.compiler)
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

dependencies {
  implementation(platform(libs.compose.bom.stable))
  implementation("androidx.compose.runtime:runtime")
}
