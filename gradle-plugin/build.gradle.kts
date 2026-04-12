plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
    alias(libs.plugins.kotlin.serialization)
}

group = "ee.schimke.composeai"
version = "0.1.0"

gradlePlugin {
    plugins {
        create("composePreview") {
            id = "ee.schimke.composeai.preview"
            implementationClass = "ee.schimke.composeai.plugin.ComposePreviewPlugin"
            displayName = "Compose Preview Plugin"
        }
    }
}

dependencies {
    implementation(libs.classgraph)
    implementation(libs.kotlinx.serialization.json)
    compileOnly("com.android.tools.build:gradle:${libs.versions.agp.get()}")

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(gradleTestKit())
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

// Functional tests use Gradle TestKit
val functionalTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

val functionalTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}

val functionalTestRuntimeOnly by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}

val functionalTestTask = tasks.register<Test>("functionalTest") {
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnit()
}

tasks.check {
    dependsOn(functionalTestTask)
}
