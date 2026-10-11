// `:usage-source-psi` — the Kotlin parser behind the usage cleaner, kept off the CLI's classpath
// (regexes guessed at structure and got it wrong). The module:
//  - compiles `compileOnly` against `kotlin-compiler-embeddable`, never a runtime dependency here;
//  - is staged into the CLI install as `lib-usage-psi/`, loaded with `lib-bta/` in one isolated
//    classloader;
//  - exposes one JSON-returning entry point, so no types are shared with the CLI.
// See `docs/design/PSI_PARSE_SPIKE.md`.
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
