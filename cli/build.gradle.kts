plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

version = providers.environmentVariable("PLUGIN_VERSION").orNull ?: "0.1.0-SNAPSHOT"

application {
    applicationName = "compose-preview"
    mainClass.set("ee.schimke.composeai.cli.MainKt")
}

tasks.named<Zip>("distZip") {
    archiveFileName.set("compose-preview-${project.version}.zip")
}

tasks.named<Tar>("distTar") {
    archiveFileName.set("compose-preview-${project.version}.tar.gz")
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
