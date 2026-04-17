plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

version = providers.environmentVariable("PLUGIN_VERSION").orNull ?: "0.1.0-SNAPSHOT"

base {
    archivesName.set("compose-preview")
}

application {
    applicationName = "compose-preview"
    mainClass.set("ee.schimke.composeai.cli.MainKt")
}

// Note: don't set `archiveFileName` directly — Gradle's distribution plugin
// uses it to derive the root directory inside the archive, so a full filename
// like `compose-preview-<version>.tar.gz` leaks the `.tar.gz` suffix into the
// extracted folder name. Setting `archiveExtension` instead lets Gradle compute
// the file name as `<archivesName>-<version>.<extension>` while keeping the
// internal root as `<archivesName>-<version>/`.
tasks.named<Tar>("distTar") {
    archiveExtension.set("tar.gz")
    compression = Compression.GZIP
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation("org.gradle:gradle-tooling-api:9.3.1")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}
