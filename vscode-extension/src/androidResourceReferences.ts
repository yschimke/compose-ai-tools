// Pure regex extractors for Android resource references that surface in
// editor text — `R.drawable.foo` / `R.mipmap.foo` in Kotlin, and
// `@drawable/foo` / `@mipmap/foo` in res-tree XML (layouts, drawable
// XML, menus, etc.). Mirrors the design of `manifestIconReferences.ts`:
// regex over full XML / Kotlin parsing because the shapes are simple
// enough and the cost of a real parser isn't worth it for the editor
// surfaces.
//
// Stays free of `vscode` imports so Mocha unit tests can drive it
// directly without an extension host.

/**
 * One drawable / mipmap reference found in source text. The hover and
 * CodeLens providers use `offset` + `length` to build the editor range,
 * and `(resourceType, resourceName)` to join against
 * `ResourceManifest.resources`.
 */
export interface ResourceRef {
    offset: number;
    length: number;
    resourceType: "drawable" | "mipmap";
    resourceName: string;
}

/**
 * Find every `R.drawable.foo` / `R.mipmap.foo` reference in [text].
 *
 * Explicitly skips `android.R.drawable.…` framework refs (the AOSP
 * resources aren't in our `resources.json`) by requiring a preceding
 * non-`.` character. `R::class.drawable` etc. is intentionally
 * unhandled — extremely rare and would need a real parser to
 * disambiguate.
 *
 * Returns refs in source order; deduping is the CodeLens / hover
 * provider's job (the hover doesn't need it, the CodeLens does).
 */
export function findKotlinResourceReferences(text: string): ResourceRef[] {
    const re = /(^|[^.\w])R\.(drawable|mipmap)\.([A-Za-z0-9_]+)\b/g;
    const out: ResourceRef[] = [];
    let match: RegExpExecArray | null;
    while ((match = re.exec(text)) !== null) {
        const leadingLen = match[1].length;
        // Start at the `R` so the editor range covers `R.drawable.foo`
        // exactly, not the character before it.
        const offset = match.index + leadingLen;
        const length = match[0].length - leadingLen;
        out.push({
            offset,
            length,
            resourceType: match[2] as "drawable" | "mipmap",
            resourceName: match[3],
        });
    }
    return out;
}

/**
 * Find every `@drawable/foo` / `@mipmap/foo` reference in an XML
 * resource file (layouts, adaptive-icon XML, menus, anything under
 * `res/`). Matches the value position of an attribute or an XML text
 * node — we only care about the reference itself, not the attribute
 * name that carries it.
 *
 * Same exclusions as the Kotlin extractor: framework references
 * (`@android:drawable/...`) and theme refs (`?attr/...`) drop out
 * because the leading-character anchor requires `@`.
 *
 * Accepts the `@+drawable/foo` resource-id form (rare but legal). The
 * `+` is consumed and not surfaced — downstream consumers care about
 * the resolved name, not the syntactic difference.
 */
export function findXmlResourceReferences(text: string): ResourceRef[] {
    // Allow whitespace before `@` so indented text-node refs match too
    // (`<item>\n    @drawable/foo\n</item>` — common in style/item XML).
    const re = /(["'>\s])@\+?(drawable|mipmap)\/([A-Za-z0-9_]+)(?=["'<\s/])/g;
    const out: ResourceRef[] = [];
    let match: RegExpExecArray | null;
    while ((match = re.exec(text)) !== null) {
        // Skip the opening delimiter character so the range starts at `@`.
        const offset = match.index + match[1].length;
        const length = match[0].length - match[1].length;
        out.push({
            offset,
            length,
            resourceType: match[2] as "drawable" | "mipmap",
            resourceName: match[3],
        });
    }
    return out;
}

/**
 * Find the [ResourceRef] in [refs] whose source range contains
 * [offset], or `null` when none does. Used by the hover provider — it
 * receives a `position`, converts to a byte offset, and asks this to
 * find the ref under the cursor.
 */
export function refAt(refs: ResourceRef[], offset: number): ResourceRef | null {
    for (const r of refs) {
        if (offset >= r.offset && offset <= r.offset + r.length) {
            return r;
        }
    }
    return null;
}

/**
 * Collapse [refs] to one entry per unique `(resourceType, resourceName)`
 * pair, keeping the first occurrence's position. Used by the CodeLens
 * provider to emit one lens per resource per file (a layout referencing
 * the same `@drawable/foo` four times shouldn't get four lenses).
 */
export function dedupRefsByResource(refs: ResourceRef[]): ResourceRef[] {
    const seen = new Set<string>();
    const out: ResourceRef[] = [];
    for (const r of refs) {
        const key = `${r.resourceType}/${r.resourceName}`;
        if (seen.has(key)) {
            continue;
        }
        seen.add(key);
        out.push(r);
    }
    return out;
}
