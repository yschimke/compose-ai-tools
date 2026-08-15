# Should the usage cleaner parse instead of scan?

`PlaygroundSourceCleaner` rewrites source with masked regex passes. It does that because the Kotlin
frontend is deliberately kept off the CLI's runtime classpath — staged into `lib-bta/` and loaded in
an isolated classloader only for an actual compile ([`cli/build.gradle.kts`](../../cli/build.gradle.kts)).

The snippet corpus made the cost of that decision measurable. Across four review rounds, nearly every
defect it surfaced was **parser-shaped**:

| Defect | What a parse knows |
|---|---|
| `previewOverrideString(key = …, default = …)` bound positionally | argument binding |
| `state.metrics.counted` taken for a package qualifier | qualified expression structure |
| `counted { }` missed — the regex required `(` | a call is a call |
| a qualified call neither rewritten nor reported | the callee, regardless of qualifier |
| `toggleable` destructuring a `Pair` | destructuring declarations |

Five symptoms, one missing thing: structure. So: measure before rewriting.

## The spike

[`PsiParseSpikeTest`](../../cli/src/test/kotlin/ee/schimke/composeai/cli/serve/PsiParseSpikeTest.kt)
answers three questions.

### 1. Does parse-only PSI work with no analysis?

Yes. `KotlinCoreEnvironment.createForProduction` with an **empty** `CompilerConfiguration` — no
classpath, no source roots, no resolution — is enough to get a `PsiFileFactory` that returns real
`KtFile`s. The expensive half of a frontend is resolution, and none of the rewrites need it.

### 2. What does it cost?

Measured over the 20 snippets `scripts/usage-corpus.sh` generates:

```
corpus            : <repo>/build/usage-corpus
environment setup : ~520 ms   (once per process)
parsed            : 20 files, 42,707 chars, in ~65 ms
per file          : ~3.2 ms
```

Against a seed path that already does a network fetch from GitHub raw, ~3 ms per file is free, and
the ~520 ms setup is one-off and lazy — it need not happen until the first seed is cleaned.

The warm-up run before the timed one has to *walk* each file, not merely create it: PSI is lazy, so
a discarded `KtFile` never builds a tree and the parser's initialisation would land inside the
measurement instead of before it.

### 3. Does the tree carry what the rewrites need?

Over those same snippets: 49 named functions, 330 call expressions, **334 named arguments**, 1412
qualified expressions. Each of those numbers is something a text pass had to infer and got wrong at
least once.

On a fixture holding every shape the review rounds broke on, the tree separates all of them:

- `previewOverrideString(key = "title", default = "Shopping")` → `default` binds to `"Shopping"` by
  its own label, in any order. **A positional call still needs the callee's parameter list** — see
  the correction below.
- `counted { }` and `counted("label")` are both call expressions.
- `ee.schimke.composeai.overrides.previewOverrideString(…)` and `state.metrics.counted { }` are the
  *same* `KtDotQualifiedExpression` shape — so the parse does not classify them, and the spike runs
  the `scaffoldPackages` allow-list over the extracted receivers to show which one it unqualifies
  and which it leaves alone. What the parse buys is a clean whole-expression key to look up, in
  place of a regex over surrounding text that guessed from the shape and got it wrong.
- `val (checked, onCheckedChange) = toggleable(…)` is a destructuring declaration, which is what the
  `$known-gaps` entry in `compose-usage.json` needs.

## The frontend never has to reach the CLI classpath

The spike also loads the parser from the BTA jars — the same artifacts the install stages into
`lib-bta/`, forwarded from the `composePreviewBta` configuration so the check runs in an ordinary
`:cli:test` rather than only after `installDist` — through a `URLClassLoader` whose
parent is the platform loader — the same isolation `PlaygroundBtaCompiler` already uses for the
compiler. Load + parse through that route: **~0.5–5 s** depending on page cache (13 jars, 70 MB), no
dependency on the CLI's own classpath. Warm, it sits near the in-process figure.

So the constraint the text passes exist to respect is not actually in the way. What was in the way
was assuming it was.

The spike's `testImplementation` on `kotlin-compiler-embeddable` is scaffolding for *typed* assertions
only, and should not survive: a real implementation wants one small module compiled `compileOnly`
against the frontend, jarred beside `lib-bta/`, and entered through a single reflective call — rather
than the reflection sprawl this spike uses to prove the route.

## Correction: a parse does not remove the `params` list

This note got the same claim wrong twice, so here it is stated carefully.

What a parse gives is the argument's own **label** — nothing more. `params` survives for two
independent reasons:

- A **positional** call carries no label at all, so only the callee's signature says which slot is
  `default`. Parse-only PSI has no resolution to supply it.
- A **labelled** call still has to reach an *indexed* template (`plain = "$1"`), and the label
  `default` does not say it is index 1. `params` is precisely that name→index map.

So a parse retires `params` only if the rule vocabulary *also* moves from `$1` to a named
placeholder like `${default}` — a separate change to the rules format, not a free consequence of
parsing. Worth knowing before anyone bets a redesign on it.

The argument for parsing rests on the other four shapes, which it settles outright.

## Three caveats, all mine

**The reflection was order-dependent.** The isolated-loader test first failed only when `PlaygroundBtaCompilerTest` ran before it, which
looked exactly like a prior in-process compile leaving global frontend state — a serious finding, had
it been true. It was not. `Class.getMethods()` has no specified order, so `methods.first { … }` was
selecting a different `createFileFromText` overload between runs. Selecting by exact signature fixed
it, and it has been stable since.

Worth recording because the false version was the more interesting story, and nothing but checking
told them apart.

**The first measurement read the wrong directory.** A Gradle test runs from its project directory, so
`build/usage-corpus` resolved to `cli/build/usage-corpus` while the script writes to
`<repo>/build/usage-corpus`. That is not a null result — it silently measured whatever was there,
which was 20 stale snippets plus 15 fixture files another test had written beside them, and got
reported as "35 snippets the corpus generates". The spike now honours `composeai.usageCorpus.out`,
falls back to the repository root, skips the fixture tree, and **prints the path it measured** so the
number can be checked rather than trusted.

**Two assertions could not fail, and one of them twice.** The destructuring check read `ktFile.text`
— the input echoed back — so it would have passed with PSI exposing nothing, which was the very
thing it existed to show. Fixed. Then the isolated-loader check was found doing the *same* thing:
stopping at `getText()`, which returns the view-provider buffer without a tree ever being built. So
the pattern outlived its first fix. Both now walk the tree and assert what they find. The rule this
file should have started with: an assertion over `text` proves nothing about a *parse*.

## What shipped

The spike said yes, so the parse is now in the cleaner. The shape is the one the spike argued for:

- **`:usage-source-psi`** — the parser, compiling `compileOnly` against `kotlin-compiler-embeddable`
  so the frontend is never a runtime dependency of anything in the main build. It exposes one method,
  `analyze(String): String`, returning JSON: calls (callee, offsets, arguments with their labels,
  trailing-lambda ranges, receiver). It also reported destructuring declarations and name
  references, for the `DESTRUCTURE` kind below; both went when that kind did. It reports **facts,
  never decisions** — `ee.schimke.composeai.overrides` and `state.metrics` are the same tree shape, so it
  hands over each receiver verbatim and lets the rules' allow-list do the classifying.
- **Staged as `lib-usage-psi/`**, loaded together with the already-staged `lib-bta/` jars in a
  `URLClassLoader` parented to the platform loader — the same isolation the playground compiler uses.
  Nothing crosses the boundary but a `String` in and JSON out, so the two sides share no classes.
- **`UsageSourceParser`** builds that loader once per process (setup is ~0.5 s, parsing ~3 ms) and
  `PlaygroundSourceCleaner` uses it for the substitution pass and the residue scan. A host with no
  staged sidecar keeps the text passes, unchanged — the parse is an upgrade where available, not a
  new hard requirement.

`:cli:test` forwards the staged jars (`composeai.usagePsi.jars`) so the parsed path is what the tests
actually exercise. Without that the cleaner would fall back silently and every test would still pass,
having covered none of it.

**On landing, the corpus ratios were unchanged: m3-catalog 3/10, meshcore-mobile 0/10, same seven
failures** (the `DESTRUCTURE` kind that followed took m3-catalog to 4/10). That
is the result to expect and the bar the spike set — parity, per snippet, before anything else. The
parse fixes shapes the text pass got wrong (an argument binding, a receiver chain, a call with no
parentheses); none of the seven remaining failures is one of those. Moving the ratio needs the new
rule vocabulary below, which the facts now make possible.

One bug found while wiring it, worth recording because it would have been silent: `KtLambdaArgument`
*is* a `KtValueArgument`, so a trailing lambda arrives in the value-argument list and takes a
positional slot — putting `{ … }` where `default` belongs. Filtered out, and pinned by a test.

## What this does and does not buy

A parse fixes the **machinery**. It does not move the ratio much on its own:

- m3-catalog's 2 destructuring failures: **done**, and the ratio moved 3/10 → 4/10. The
  `DESTRUCTURE` rule kind read the facts' entries and initialiser; `compose-usage.json`'s
  `$known-gaps` entry is retired. **Since removed** — m3-catalog's helpers now return a
  `MutableState` used through `by`, so the same snippets come out of an ordinary `SUBSTITUTE` rule
  and the kind had no consumer left. The facts it needed (`destructurings`, `references`) went with
  it.
- The 2 conditional-`stringResource` failures become tractable for the same reason: the inliner can
  see the `if` rather than declining on a regex.
- `CatalogFilledStars` (×4) still needs a substitution rule — no parse invents an icon.
- meshcore-mobile's 0/10 stays structural: its previews compose app screens over fixture data, so
  there is no library call site to reduce to.

The corpus is what makes the rewrite safe to attempt: **3/10 and 0/10 are a baseline**, per snippet,
that a parser-backed cleaner has to match or beat before it lands.
