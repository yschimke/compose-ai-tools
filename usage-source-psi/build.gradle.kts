// `:usage-source-psi` — the Kotlin **parser** behind the usage cleaner, kept off the CLI's
// classpath.
//
// `PlaygroundSourceCleaner` used to answer every structural question with regex, because the Kotlin
// frontend is deliberately not a CLI dependency (see `cli/build.gradle.kts`'s `lib-bta/` note). The
// snippet corpus showed what that cost: named-argument binding, receiver chains mistaken for
// package
// qualifiers, trailing-lambda calls with no parentheses, qualified calls no pass could see. All
// structure, all guessed at.
//
// So the parse lives here instead, in a module that:
//  - compiles `compileOnly` against `kotlin-compiler-embeddable` — the frontend is never a
// *runtime*
//    dependency of anything in the main build;
//  - is staged into the CLI install as `lib-usage-psi/`, loaded alongside the already-staged
//    `lib-bta/` jars in one isolated classloader;
//  - exposes exactly one entry point returning JSON, so the loader needs no shared types with the
//    CLI and the reflective surface is a single method.
//
// See `docs/design/PSI_PARSE_SPIKE.md` for the measurements this design came from.
plugins {
  id("composeai.base-conventions")
  alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
  // compileOnly, and it must stay that way: the frontend reaches the runtime only via the staged
  // `lib-bta/` jars in the isolated loader. A plain `implementation` here would put a compiler
  // frontend on the classpath of everything that depends on this module.
  compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
}
