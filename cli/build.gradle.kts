plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("ee.schimke.composeai.cli.MainKt")
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
