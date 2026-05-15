// Resolve the DOM target for a `legend-selected` event so the host
// can call `scrollIntoView` on it. The lookup is intentionally
// scoped to the `<data-tabs>` subtree — never the whole document —
// because `<box-overlay>` elements painted on top of the preview
// image carry the same `data-bundle` attribute as the bundle's tab
// body, and the overlay descendants carry the same
// `data-overlay-id` as the table row's `data-legend-id`. A
// document-wide selector returns the first match in tree order,
// which puts the overlay box (high in the DOM, above `<data-tabs>`)
// ahead of the table row — `scrollIntoView` then runs on the
// overlay and the table row never moves.
//
// Two surfaces are accepted inside the tab body: `data-legend-id`
// (the canonical one, set via `<data-table>.setOverlayId`) and
// `data-overlay-id` (the fallback for tables that haven't wired the
// mirroring yet — kept so the host doesn't crash on rows from
// pre-legend bundles).

export function findLegendTarget(
    dataTabs: HTMLElement,
    bundleId: string,
    entryId: string,
): HTMLElement | null {
    const tabBody = dataTabs.querySelector<HTMLElement>(
        `[data-bundle="${bundleId}"]`,
    );
    if (!tabBody) return null;
    return tabBody.querySelector<HTMLElement>(
        `[data-legend-id="${entryId}"], [data-overlay-id="${entryId}"]`,
    );
}
