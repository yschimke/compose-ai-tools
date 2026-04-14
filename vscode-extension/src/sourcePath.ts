import * as fs from 'fs';
import * as path from 'path';

// Matches a top-level Kotlin package declaration. Tolerates optional trailing
// semicolon and leading whitespace (annotations/comments above `package` are
// handled by the `m` flag anchoring to line starts elsewhere in the file).
const PACKAGE_RE = /^\s*package\s+([a-zA-Z_][\w.]*)\s*;?\s*$/m;

/**
 * Builds the package-qualified source path used as the `sourceFile` field in
 * the Gradle plugin's `previews.json` — e.g. `com/example/samplewear/Previews.kt`.
 *
 * Derived from the file's own `package` declaration so two files with the same
 * basename in different packages don't collide. Files in the default package
 * (or unreadable files) fall back to the bare basename.
 */
export function packageQualifiedSourcePath(filePath: string): string {
    const basename = path.basename(filePath);
    try {
        const content = fs.readFileSync(filePath, 'utf-8');
        const m = PACKAGE_RE.exec(content);
        if (m) {
            return m[1].replace(/\./g, '/') + '/' + basename;
        }
    } catch { /* fall through to basename */ }
    return basename;
}
