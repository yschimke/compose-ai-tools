// "Where does it belong?" — the report form's third question, and the one line of the issue body
// that answers it in prose.
//
// The answer's real transport is the `<select>` itself: its value is the form's `labels` parameter,
// so GitHub applies a `parity:` label whether or not this file ever runs. What this adds is the
// same fact where a reader of the issue will actually see it, and where it survives a repository
// that has no such label to apply — see `ServeIssueReport.CLASSIFICATION_PREFIX` for why the server
// writes the line pointing at the label rather than pre-writing an answer it cannot know.

/** The line's fixed opening. Kept in step with `ServeIssueReport.CLASSIFICATION_PREFIX`. */
export const CLASSIFICATION_PREFIX = "**Where it belongs:** ";

/**
 * [body] with the classification line restated as [sentence].
 *
 * Matched by PREFIX on a whole line, and only the first such line: the body around it is written by
 * the server, and the one place catalog-authored text reaches it — preview ids, variants, a source
 * path — arrives inside table cells and locator fields, none of which can begin a line with this
 * prefix. A body with no such line (an older server's, or one this build does not write) is
 * returned untouched rather than grown a line the form never asked for.
 *
 * A blank [sentence] leaves the body alone, which is the honest reading of "the control told us
 * nothing": the server's own line already points at the label, and replacing it with an empty
 * assertion would be strictly worse than the sentence it overwrote.
 */
export function withClassification(body: string, sentence: string): string {
    if (!sentence) return body;
    const lines = body.split("\n");
    const at = lines.findIndex((line) =>
        line.startsWith(CLASSIFICATION_PREFIX),
    );
    if (at < 0) return body;
    lines[at] = CLASSIFICATION_PREFIX + sentence;
    return lines.join("\n");
}
