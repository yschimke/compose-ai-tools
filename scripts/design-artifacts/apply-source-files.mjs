/**
 * Re-stamp each component's `sourceFile` (the module-relative path of the `@Preview`
 * function's source file) onto the built catalog manifest.
 *
 * Discovery records `sourceFile` per preview in the bundle's `previews.json`, but the
 * pinned `@design-parity/catalog-export` (`buildCatalog`) never reads it, so it doesn't
 * survive onto the emitted component. Without it the preview server has no per-preview
 * source path to link to — the catalog viewer's "source" link can't be built. This closes
 * that gap in the same post-process pass that stamps `section` / `livePreview` / `display`
 * onto the written `catalog.json` (mirrors {@link file://./apply-spec-sections.mjs}).
 *
 * The path is resolved by joining two lookups: the spec maps each `componentId` to the
 * `preview` function it renders, and [sourceByFn] (built by the generator's
 * `sourceByFunction`) maps a function name to its recorded `sourceFile`. A component whose
 * function carried no path (discovery didn't record one, or an older bundle) is left
 * untouched — the server then simply renders no link for it.
 *
 * ## A spec may declare the path itself, and then it wins
 *
 * The join above answers "where is the `@Preview` function?", and for almost every catalog that is
 * also "where should a reader be sent". A catalog of **call sites** breaks the two apart. The
 * AndroidX samples catalogs generate one `@Preview` wrapper per sample —
 * `fun ButtonSamplePreview() = androidx.wear.compose.material3.samples.ButtonSample()` — because
 * only 34 of 170 Wear samples carry `@Preview` upstream. Discovery is right that the preview lives
 * in the generated file; a reader opening the Source panel wants the sample, and three lines of
 * generated delegation is the one thing on that page nobody came for.
 *
 * So a spec component may carry its own `sourceFile` (and `bodyLine`), and it takes precedence over
 * both the discovery join and anything a newer exporter preserved. Precedence rather than fallback
 * is the whole point: the catalog is not filling a gap the join left, it is overriding an answer the
 * join got right for a different question. Nothing else changes — the identity fields still describe
 * the producing module, because that is still where the preview was compiled from.
 *
 * Inference was considered and rejected. Discovery's target inference does not fire on these
 * previews at all (`PreviewTargetInference` filters the sample's own package as library code, since
 * `androidx.wear.compose.material3.samples` sits under the `androidx.wear.compose.material3.`
 * wrapper prefix), and widening that heuristic to serve one catalog would change the target of
 * previews in every other. A catalog that generates its own wrappers knows exactly what each one
 * delegates to; saying so is cheaper and truer than asking a scorer to guess it back.
 *
 * Additive and idempotent:
 *  - only components whose spec function resolves to a `sourceFile` are touched;
 *  - a component that already carries a `sourceFile` is left as-is (never clobbered).
 *
 * The `sourceFile` is module-relative (`src/main/kotlin/…/Foo.kt`). `sourceModule` rides beside it
 * for repository-wide catalogs, whose components can come from different Gradle projects; the
 * server falls back to the catalog-wide source module for older single-module exports.
 *
 * `bodyLine` — a line inside the preview function's body — rides along on the same join,
 * for the same reason and to the same place. It is what lets the playground handoff open
 * the one declaration a visitor clicked instead of the whole section file it shares with
 * its group. Stamped only alongside a `sourceFile` (a line number with no file is
 * meaningless), and the server treats its absence as "seed the whole file".
 *
 * @param {{components?: Array<{componentId: string, sourceFile?: string, sourceModule?: string, bodyLine?: number}>}} manifest
 *   The parsed `catalog.json`, mutated in place.
 * @param {{groups?: Array<{components?: Array<{componentId: string, preview?: string,
 *   sourceFile?: string, bodyLine?: number}>}>}} spec
 *   The catalog spec the manifest was built from. A component's own `sourceFile` / `bodyLine`, when
 *   it declares them, override the join for that component.
 * @param {Map<string, {sourceFile?: string, bodyLine?: number, module?: string}>} sourceByFn
 *   Function-name → source lookup (the generator's `sourceByFunction(bundle)`).
 * @returns {number} how many components had a `sourceFile` newly stamped.
 */
export function applySourceFiles(manifest, spec, sourceByFn) {
  if (!sourceByFn || sourceByFn.size === 0) return 0;

  const previewByComponentId = new Map();
  // What the SPEC says about a component's source, as opposed to what discovery found. Only
  // components that declare a path are here, so the ordinary catalog's map is empty and the join
  // below behaves exactly as it did.
  const declaredByComponentId = new Map();
  for (const group of spec?.groups ?? []) {
    for (const component of group.components ?? []) {
      if (component.preview) {
        previewByComponentId.set(component.componentId, component.preview);
      }
      if (typeof component.sourceFile === "string" && component.sourceFile.length > 0) {
        declaredByComponentId.set(component.componentId, {
          sourceFile: component.sourceFile,
          bodyLine:
            typeof component.bodyLine === "number" && component.bodyLine > 0
              ? component.bodyLine
              : undefined,
        });
      }
    }
  }

  let stamped = 0;
  for (const component of manifest?.components ?? []) {
    const fn = previewByComponentId.get(component.componentId);
    const source = fn ? sourceByFn.get(fn) : undefined;
    const declared = declaredByComponentId.get(component.componentId);
    if (declared) {
      // Before every other branch, and unconditionally: a declared path is an override, so it must
      // win over a value the exporter preserved as surely as over the join. `bodyLine` is cleared
      // rather than left when the declaration carries none — a line from the OTHER file would slice
      // the panel at an arbitrary point of this one, which reads as a rendering bug rather than a
      // missing field.
      const changed = component.sourceFile !== declared.sourceFile;
      component.sourceFile = declared.sourceFile;
      if (declared.bodyLine === undefined) delete component.bodyLine;
      else component.bodyLine = declared.bodyLine;
      // The identity fields still describe where the preview was COMPILED from, which the override
      // does not change — the module and directory are the samples module either way.
      stampIdentity(component, source);
      if (typeof source?.module === "string" && source.module.length > 0) {
        component.sourceModule ??= source.module;
      }
      if (changed) stamped += 1;
      continue;
    }
    if (component.sourceFile !== undefined) {
      // A newer exporter may already preserve sourceFile. Add the matching module identity without
      // replacing the path; never pair a module with a different pre-existing file.
      if (
        component.sourceModule === undefined &&
        source?.sourceFile === component.sourceFile &&
        typeof source.module === "string" &&
        source.module.length > 0
      ) {
        component.sourceModule = source.module;
      }
      // The same identity fields, on the same condition: the exporter kept the path, so the module
      // that produced it is still the one this join resolved.
      if (source?.sourceFile === component.sourceFile)
        stampIdentity(component, source);
      continue;
    }
    if (source?.sourceFile) {
      component.sourceFile = source.sourceFile;
      if (typeof source.module === "string" && source.module.length > 0) {
        component.sourceModule = source.module;
      }
      // Only with a path, and only when discovery actually recorded one — an older bundle
      // carries no `bodyLine`, and a component with a line but no file cannot be opened.
      if (typeof source.bodyLine === "number" && source.bodyLine > 0) {
        component.bodyLine = source.bodyLine;
      }
      stampIdentity(component, source);
      stamped += 1;
    }
  }
  return stamped;
}

/**
 * The two identity fields a consumer needs to build a REPOSITORY path and a source anchor, neither
 * of which is recoverable from what the catalog published before.
 *
 * * `sourceDirectory` — the producing project's directory relative to the repository root, as the
 *   BUNDLE recorded it. `sourceModule` beside it is a LOGICAL Gradle path, and
 *   `project(":x").projectDir = file("a/b")` may put the project anywhere: this repository remaps
 *   100 projects and not one derives correctly from its path. Joined to the module-relative
 *   `sourceFile`, this is what makes a repository path true rather than plausible.
 * * `sourceFunction` — discovery's own `@Preview` function name. `buildVariantSuffix` appends an
 *   arbitrary `@Preview(name = …)` / `group` through `sanitizeForPath`, which passes spaces and
 *   dots through verbatim, so a preview id does not split back into function and label. Carrying
 *   the name is the only way to state it.
 *
 * Written only when the producer supplied them, so an older bundle stamps neither and its consumers
 * behave exactly as they did.
 */
function stampIdentity(component, source) {
  // A string, INCLUDING the empty one. `""` is the root project — a real, usable answer meaning
  // "already repository-relative" — while `undefined` is a bundle that never recorded the field.
  // Requiring a non-empty value stamped nothing for a root-project catalog, so its handles were
  // dropped as if the directory were unknown.
  if (
    component.sourceDirectory === undefined &&
    typeof source?.directory === "string"
  ) {
    component.sourceDirectory = source.directory;
  }
  if (
    component.sourceFunction === undefined &&
    typeof source?.functionName === "string" &&
    source.functionName.length > 0
  ) {
    component.sourceFunction = source.functionName;
  }
}
