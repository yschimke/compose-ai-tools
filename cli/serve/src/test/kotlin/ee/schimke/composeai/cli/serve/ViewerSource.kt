/*
 * Copyright 2025 Yuri Schimke
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ee.schimke.composeai.cli.serve

import java.io.File

/**
 * Walk up from the test working dir to the repo root.
 *
 * Anchored on `settings.gradle.kts` rather than a count of `..` segments, which is what let the
 * serve tests survive the move into `:cli:serve` — the working directory went from `cli/` to
 * `cli/serve/`, and every sibling assertion that had spelled a repo path as `File("../scripts/…")`
 * silently pointed a level too high. Those now come through here.
 */
internal fun repoRoot(): File {
  var dir: File? = File(System.getProperty("user.dir")).absoluteFile
  while (dir != null) {
    if (File(dir, "settings.gradle.kts").isFile) return dir
    dir = dir.parentFile
  }
  error("could not locate repo root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
}

/**
 * The viewer's TypeScript source — where the assertions that pin how the viewer is WRITTEN look.
 *
 * `assets/viewer.js` is a generated, minified bundle: `esbuild` renames every identifier to two
 * letters, so `function ownsUrlParam(name)` is not in it and neither is anything else these
 * assertions name. Worse than the failures are the ones that would keep passing — a *negative*
 * assertion ("the viewer must not still spell it the old way") is vacuously true against minified
 * text, so pointing those at the bundle would quietly retire them.
 *
 * Reading the source is safe because the two cannot drift: `npm run verify` in `cli/serve-web`
 * rebuilds the bundle and fails if the committed bytes differ from what this file produces, and CI
 * runs it on every PR.
 *
 * These assertions pin SPELLING, which is brittle by nature — a rename breaks them without anything
 * being wrong. Where the behaviour can be driven instead, it should be: the DOM-free rules the
 * viewer calls all live in `cli/serve-web/src/viewer/` with real tests beside them, and a rule that
 * moves there stops needing a grep here at all.
 */
internal fun viewerSource(): String = File(repoRoot(), "cli/serve-web/src/viewer.ts").readText()

/**
 * [viewerSource] with every run of whitespace collapsed to a single space.
 *
 * Prettier reflows `src/viewer.ts` to 80 columns, so a predicate that reads as one line in the
 * source can be split across five — `canServerRender` is one `&&` per line. An assertion about what
 * the viewer DECIDES should not care where the line breaks fell, and flattening is what lets it go
 * on saying `"!onWasm && !onRc && !onFixedFrame"` and mean exactly that, rather than degrading into
 * three unanchored `contains` that would also pass if the three moved apart.
 */
internal fun viewerSourceFlat(): String = viewerSource().replace(Regex("\\s+"), " ")
