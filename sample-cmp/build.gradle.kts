@file:Suppress("DEPRECATION")

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    id("ee.schimke.composeai.preview")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation(compose.uiTooling)
    implementation(compose.components.uiToolingPreview)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}
