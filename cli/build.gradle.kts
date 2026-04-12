plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    id("org.graalvm.buildtools.native") version "0.10.6"
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

graalvmNative {
    binaries {
        named("main") {
            imageName.set("compose-preview")
            mainClass.set("ee.schimke.composeai.cli.MainKt")
            buildArgs.addAll(
                "--no-fallback",
                "-H:+ReportExceptionStackTraces",
            )
        }
    }
}
