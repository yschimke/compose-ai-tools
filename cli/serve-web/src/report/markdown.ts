// What a *picked element* is worth in an issue body, besides its pixels.
//
// The element picker exists because on these pages the interesting thing usually has an exact
// boundary — a render, a spec panel, one diagnostics table, one cell of one. For most of those a
// picture is the whole answer. For a TABLE it is not: a triager who wants to read the numbers back,
// grep them, or quote one row cannot do any of that with a screenshot, and `/status`, the parity
// dashboard and the report page itself are all mostly tables. So a picked table yields BOTH — the
// crop, and the same table as markdown the reporter can paste as text.
//
// Everything here is a pure DOM read, which is what keeps it testable against happy-dom rather than
// against a browser that can screenshot.

/** Cell text, made safe inside a markdown table.
 *
 * The three characters and the ORDER are the same rule the server's own report escaping uses (see
 * `ServeBugReport.cell`) and the browser block in `chrome/bugReport.ts`: backslash first, or it
 * doubles the escapes added after it; `|` shears the row; a backtick would close a code span and
 * let the rest of the cell render as markdown. A newline inside a cell shears the TABLE, so it
 * collapses to a space.
 */
export function cell(text: string): string {
    return text
        .replace(/\\/g, "\\\\")
        .replace(/\|/g, "\\|")
        .replace(/`/g, "\\`")
        .replace(/\s+/g, " ")
        .trim();
}

/** The nearest table an element belongs to, itself included. */
function ownerTable(el: Element): HTMLTableElement | null {
    return el.closest("table");
}

function rowsOf(table: HTMLTableElement): HTMLTableRowElement[] {
    return Array.from(table.querySelectorAll("tr"));
}

function cellsOf(row: HTMLTableRowElement): string[] {
    return Array.from(row.querySelectorAll("th,td")).map((c) =>
        cell(c.textContent || ""),
    );
}

/**
 * A whole table as markdown.
 *
 * The header row is whichever row is made of `<th>` — and when none is, an EMPTY header is emitted
 * rather than promoting the first row of data into one. GitHub will not render a pipe table with no
 * header at all, and quietly eating the first row of a report's numbers is a worse failure than a
 * blank header line: this server's own fact tables (`/status`, the report page) are exactly that
 * shape, two columns with row headers and no column heading.
 */
export function tableMarkdown(table: HTMLTableElement): string {
    const rows = rowsOf(table)
        .map(cellsOf)
        .filter((r) => r.length > 0);
    if (!rows.length) return "";
    const width = rows.reduce((w, r) => Math.max(w, r.length), 0);
    const pad = (row: string[]) =>
        Array.from({ length: width }, (_, i) => row[i] ?? "");
    const headerIsHeadings = Array.from(
        rowsOf(table)[0]?.querySelectorAll("th,td") ?? [],
    ).every((c) => c.tagName === "TH");
    const header = headerIsHeadings ? pad(rows[0]) : pad([]);
    const body = headerIsHeadings ? rows.slice(1) : rows;
    const line = (row: string[]) => `| ${row.join(" | ")} |`;
    return [
        line(header),
        line(Array.from({ length: width }, () => "---")),
        ...body.map((row) => line(pad(row))),
    ].join("\n");
}

/**
 * What a picked element contributes as *text*, or `""` when a picture is the whole story.
 *
 * Deliberately narrow. A picked button, render or panel returns nothing: its markdown would be a
 * paragraph of chrome nobody asked for, and the capture already says it better. Only the two shapes
 * whose value is in being re-readable are handled — a table (or any cell of one, which carries its
 * whole table so a single number keeps the row and column that name it), and preformatted text,
 * which on these pages is a stack trace or a render error.
 */
export function elementMarkdown(el: Element): string {
    const table = ownerTable(el);
    if (table) return tableMarkdown(table);
    const pre = el.closest("pre");
    if (pre) {
        const text = (pre.textContent || "").replace(/\s+$/, "");
        // Any fence inside the text would close the block early and let a stack trace render as
        // markdown — the same neutralisation the server applies to failure lines.
        return text ? "```\n" + text.replace(/```/g, "'''") + "\n```" : "";
    }
    return "";
}

/**
 * How the capture list names a picked element: its tag, plus the first thing that identifies it.
 *
 * An `id` if it has one, else its first class, else nothing. Truncated hard, because this is a
 * label in a narrow panel and a catalog's class attribute can be long enough to break the layout of
 * the list it is supposed to be labelling.
 */
export function elementLabel(el: Element): string {
    const tag = el.tagName.toLowerCase();
    const id = el.id ? `#${el.id}` : "";
    const cls = !id && el.classList.length ? `.${el.classList[0]}` : "";
    const name = `${tag}${id}${cls}`;
    return name.length > 40 ? `${name.slice(0, 39)}…` : name;
}
