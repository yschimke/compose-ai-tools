// Pure regex-based extractor for the FQN(s) of top-level classes in a
// Kotlin source file, plus a hint for "is this class an Android
// component" (Activity / Service / Receiver / Provider subclass). Used
// by [ActivityIconHoverProvider] to look up the manifest icon for the
// file the user is editing.
//
// Lives outside the VS Code surface so Mocha unit tests can drive it
// without the extension host — Mocha can't load `vscode` outside an
// extension host. The thin wrapper in the hover provider reads document
// text and forwards.
//
// Why regex instead of `vscode.executeDocumentSymbolProvider`:
// activity/service/receiver/provider classes always live at the top
// level of a file and have predictable shapes. The LSP path is async
// and only available when a Kotlin language server is attached — the
// regex path works even in pure-text mode and is the same trade-off
// `findManifestIconReferences` makes for the manifest side.

/**
 * One top-level class declaration. The hover provider needs both the
 * class name (to compose into an FQN against the file's `package`) and
 * the textual position so it can hang the hover off the class-name
 * range rather than firing across the whole class body.
 */
export interface KotlinClassDeclaration {
    /** Simple class name — `MainActivity`, never the FQN. */
    name: string;
    /** Byte offset of the class-name identifier within the source. */
    nameOffset: number;
    /** Length of the class-name identifier (always `name.length`). */
    nameLength: number;
    /**
     * The substring from the start of the `class` keyword to the next
     * `{` or end of line, inclusive of any `: Base()` superclass clause.
     * Used by [isActivityLikeDeclaration] to recognise Android
     * components without bringing in a parser.
     */
    declaration: string;
}

const PACKAGE_RE = /^\s*package\s+([\w.]+)\s*;?\s*$/m;

/**
 * Recognised Kotlin modifiers + annotation prefixes that may appear
 * between the start of a line and the `class` keyword. Doesn't have to
 * be exhaustive — anything we miss falls through to "no class found
 * here" which is the same as the user not having an Activity class on
 * that line.
 */
const CLASS_MODIFIERS =
    "(?:public|internal|private|protected|open|final|abstract|sealed|data|inner|enum|annotation|inline|value|companion)";

/**
 * Match `class Name` declarations anywhere in the file, capturing the
 * preceding whitespace + modifiers + annotations chain. Multiline /
 * gm so we can iterate across the file. Annotation args are matched
 * non-greedily and limited to one line to avoid swallowing the file on
 * a malformed source — the LSP handles the real parsing; we just need
 * the obvious shapes.
 */
const CLASS_RE = new RegExp(
    "^\\s*" +
        "(?:" +
        "@[\\w.]+(?:\\([^\\n)]*\\))?\\s+" +
        "|" +
        CLASS_MODIFIERS +
        "\\s+" +
        ")*" +
        "class\\s+([A-Z][\\w]*)",
    "gm",
);

const ACTIVITY_LIKE_BASE_RE =
    /\b(?:ComponentActivity|FragmentActivity|AppCompatActivity|Activity|Service|BroadcastReceiver|ContentProvider)\b/;

/** Extract `package x.y.z` from the file, or `null` when none is declared. */
export function extractPackage(source: string): string | null {
    const m = PACKAGE_RE.exec(source);
    return m ? m[1] : null;
}

/**
 * Walk [source] and return every `class Name` declaration that looks
 * top-level (matched by [CLASS_RE]). May include nested classes the
 * regex couldn't disambiguate from top-level — downstream the manifest
 * lookup filters those out (no FQN match = no icon shown), so a small
 * amount of over-match is fine.
 */
export function extractClassDeclarations(
    source: string,
): KotlinClassDeclaration[] {
    const out: KotlinClassDeclaration[] = [];
    CLASS_RE.lastIndex = 0;
    let match: RegExpExecArray | null;
    while ((match = CLASS_RE.exec(source)) !== null) {
        const name = match[1];
        // Locate the identifier within the matched substring so the
        // hover can fire on the class name itself, not the whole line.
        const nameInMatchOffset = match[0].lastIndexOf(name);
        const nameOffset = match.index + nameInMatchOffset;
        const declarationEnd = findDeclarationEnd(source, match.index);
        const declaration = source.substring(match.index, declarationEnd);
        out.push({
            name,
            nameOffset,
            nameLength: name.length,
            declaration,
        });
    }
    return out;
}

/** Pair [extractPackage] with [extractClassDeclarations] to produce FQNs. */
export function topLevelClassFqns(source: string): string[] {
    const pkg = extractPackage(source);
    return extractClassDeclarations(source).map((decl) =>
        pkg ? `${pkg}.${decl.name}` : decl.name,
    );
}

/**
 * True iff the [declaration] mentions one of the platform component
 * base classes — used to decide whether the `<application>` icon is a
 * sensible fallback when the file's FQN has no manifest override. Plain
 * substring match: we'd rather miss an unusual base (and skip the
 * fallback) than show the app icon next to a random data class.
 */
export function isActivityLikeDeclaration(
    decl: KotlinClassDeclaration,
): boolean {
    return ACTIVITY_LIKE_BASE_RE.test(decl.declaration);
}

/**
 * Walk forward from the `class` keyword to the next `{` or `\n\n` (end
 * of declaration). Bounded to the first 4 lines so a malformed source
 * doesn't run away — superclass clauses with constructor args don't
 * usually exceed two physical lines.
 */
function findDeclarationEnd(source: string, classStartIndex: number): number {
    const maxLineLookahead = 4;
    let idx = classStartIndex;
    let linesSeen = 0;
    while (idx < source.length) {
        const ch = source.charAt(idx);
        if (ch === "{") {
            return idx;
        }
        if (ch === "\n") {
            linesSeen += 1;
            if (linesSeen >= maxLineLookahead) {
                return idx;
            }
        }
        idx += 1;
    }
    return source.length;
}
