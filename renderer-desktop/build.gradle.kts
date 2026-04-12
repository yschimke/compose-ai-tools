@file:Suppress("DEPRECATION")

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.runtime)
    implementation(compose.components.uiToolingPreview)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}
