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
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}
