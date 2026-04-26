/**
 * One icon-bearing attribute on a manifest line. Public so the
 * [AndroidManifestCodeLensProvider] consumer and its unit tests share a single shape, and so
 * future surfaces (hover, doctor diagnostic) can reuse the parser without round-tripping through
 * VS Code APIs.
 */
export interface ManifestIconMatch {
    /** Byte offset of the matched attribute within the document text. */
    offset: number;
    /** Local attribute name without the `android:` prefix — `icon` / `roundIcon` / `logo` / `banner`. */
    attribute: 'icon' | 'roundIcon' | 'logo' | 'banner';
    /** `drawable` or `mipmap`. */
    resourceType: 'drawable' | 'mipmap';
    /** Resource name without the `@type/` prefix. */
    resourceName: string;
}

/**
 * Walks [text] and emits one [ManifestIconMatch] per icon-bearing manifest attribute that points
 * at a `@drawable/` or `@mipmap/` resource. Plain regex rather than full XML parsing — manifests
 * are simple enough that the regex correctly handles the common forms (single-line attribute,
 * attributes split across lines, both quote styles), and the Kotlin-side
 * `ManifestReferenceExtractor` is the canonical extractor for tooling that needs full fidelity.
 *
 * Lives outside the CodeLens provider so unit tests don't pull in the VS Code module — the
 * extension test environment has it; plain Mocha doesn't.
 */
export function findManifestIconReferences(text: string): ManifestIconMatch[] {
    const out: ManifestIconMatch[] = [];
    const re = /android:(icon|roundIcon|logo|banner)\s*=\s*["'](@(?:\+)?(drawable|mipmap)\/([A-Za-z0-9_]+))["']/g;
    let match: RegExpExecArray | null;
    while ((match = re.exec(text)) !== null) {
        out.push({
            offset: match.index,
            attribute: match[1] as ManifestIconMatch['attribute'],
            resourceType: match[3] as ManifestIconMatch['resourceType'],
            resourceName: match[4],
        });
    }
    return out;
}
