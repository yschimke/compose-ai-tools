// Focus-mode inspector — the side panel that appears when a single
// preview is focused. Three sections, top to bottom:
//
//   1. **Suggested for this preview** — a short chip row driven by
//      annotation hints (Wear device, scroll capture, existing a11y
//      findings, non-default UI mode) plus the per-scope MRU. Capped
//      at MAX_SUGGESTIONS so the eye lands on it. Cheap kinds may
//      auto-enable when `composePreview.autoEnableCheap.enabled` is on.
//
//   2. **Five stable buckets** (Accessibility / Layout / Performance
//      / Theming / Resources, plus a fallback "More" for unknown
//      namespaces) — each `<details>` collapses by default; expanding
//      reveals the kinds the daemon actually advertises (or, today,
//      the built-in placeholder set). The chrome stays at five rows
//      regardless of how many extensions register on the daemon
//      side; only the contents change.
//
//   3. **History** + **Tools** — diff buttons (HEAD / main) and
//      action buttons (a11y, device, live, record). Unchanged from
//      the previous flat-picker layout.
//
// All taxonomy / cheapness / suggestion logic lives in
// `focusProductTaxonomy.ts` so it's testable without a DOM. This
// module is the imperative DOM glue plus the per-scope MRU map.

import type {
    AccessibilityFinding,
    AccessibilityNode,
    PreviewInfo,
} from "../shared/types";
import {
    BUCKET_META,
    MAX_SUGGESTIONS,
    PRODUCT_BUCKETS,
    type ProductBucket,
    type ProductDescriptor,
    bucketOf,
    bumpMru,
    costOf,
    groupByBucket,
    suggestFor,
} from "./focusProductTaxonomy";
import {
    type PresenterContext,
    type PresentationError,
    type PresentationLegend,
    type PresentationReport,
    type ProductPresentation,
    getPresenter,
} from "./focusPresentation";
import { LegendArrowController } from "./legendArrow";

export interface FocusInspectorConfig {
    /** The `<div id="focus-inspector">` element rendered by `<preview-app>`. */
    el: HTMLElement;
    /** Whether `composePreview.earlyFeatures` is on — when off the
     *  inspector hides and the caller's `clear` path clears it. */
    earlyFeatures(): boolean;
    /** Whether `composePreview.autoEnableCheap.enabled` is on. When
     *  true, suggested cheap kinds auto-enable on first sight per
     *  scope. Always-false fallback is safe. */
    autoEnableCheap(): boolean;
    /** Look up the manifest entry for a preview, or `undefined` when
     *  the preview is unknown to this panel. */
    getPreview(previewId: string): PreviewInfo | undefined;
    /** Latest a11y findings for a preview. */
    getA11yFindings(previewId: string): readonly AccessibilityFinding[];
    /** Latest a11y hierarchy nodes for a preview. */
    getA11yNodes(previewId: string): readonly AccessibilityNode[];
    /** Latest daemon data product payload for preview/kind. */
    getDataProduct?(previewId: string, kind: string): unknown;
    /** `previewId` whose a11y overlay subscription is currently on. */
    getA11yOverlayId(): string | null;
    /** Whether [previewId] is currently in the live (interactive) set. */
    isLive(previewId: string): boolean;
    /** Click handlers shared with the focus toolbar. */
    onToggleA11yOverlay(): void;
    /** `shift = false` matches the toolbar Live button — single-target. */
    onToggleInteractive(shift: boolean): void;
    onToggleRecording(): void;
    onRequestFocusedDiff(against: "head" | "main"): void;
    onRequestLaunchOnDevice(): void;
    /**
     * Fired when the user toggles a daemon-backed data-extension row in
     * the bucket list (or its suggestion chip). The wiring posts a
     * `setDataExtensionEnabled` message so the extension can
     * subscribe/unsubscribe against the daemon for `(previewId, kind)`,
     * which causes the next render to attach (or stop attaching) the
     * payload. The inspector itself paints a placeholder immediately —
     * background fetch fills the real contribution on the next render.
     *
     * `local/*` kinds that have their own dedicated wiring
     * (`local/a11y/overlay`, `local/render/error`) never reach this
     * callback — they short-circuit in `handleChipClick` /
     * `collectPresentations` before we get here. Optional so existing
     * tests don't have to thread a stub through every harness.
     */
    onToggleDataExtension?(
        previewId: string,
        kind: string,
        enabled: boolean,
    ): void;
    /**
     * Scope key for MRU. Two previews in the same module share an MRU
     * list; switching modules resets effective ranking. Empty string
     * is a valid sentinel (treated as a single shared scope).
     */
    getScope(): string;
    /**
     * Read previously-persisted MRU for [scope]. Called once per scope
     * the first time the inspector renders for it; the controller
     * caches the result in memory and only writes back on changes.
     * Implementations should return an empty array for unknown scopes.
     */
    loadMru(scope: string): readonly string[];
    /**
     * Persist the in-memory MRU for [scope]. Called from `toggleProduct`
     * after `bumpMru`. Implementations are expected to debounce / batch
     * if the underlying store is expensive — the inspector calls this
     * synchronously on every toggle.
     */
    saveMru(scope: string, mru: readonly string[]): void;
}

export class FocusInspectorController {
    /**
     * Per-preview map of kinds the user has explicitly enabled in the
     * focus inspector. Scope is intentionally per-previewId — toggling a
     * data extension only affects the currently focused preview, never
     * the rest of the grid (a future "apply to all" affordance would
     * write through this map for every visible card). Keeps the daemon
     * subscription set tight so we don't keep producing data for
     * previews the user has navigated away from.
     */
    private readonly enabledByPreview = new Map<string, Set<string>>();
    /** Per-scope MRU: scope -> kinds, most-recent first. In-memory
     *  for now; persistence via `vscode.setState` is a follow-up. */
    private readonly mruByScope = new Map<string, string[]>();
    /** Per-scope set of kinds we've already auto-enabled this session,
     *  so `autoEnableCheap` doesn't keep flipping a kind back on after
     *  the user explicitly turned it off. */
    private readonly autoEnabledByScope = new Map<string, Set<string>>();
    /** Daemon-advertised products. Replaces the built-in placeholders
     *  when set. `null` means "use built-in fallback". Pushed in via
     *  `setProducts`. */
    private daemonProducts: ProductDescriptor[] | null = null;
    private suggestionsOpen = true;
    private suggestedKinds = new Set<string>();
    private bucketOpen: Partial<Record<ProductBucket, boolean>> = {};
    private historyOpen = false;
    /** Last card we rendered. Held so `toggleProduct` can re-render
     *  the panel on the same card after flipping a checkbox. */
    private lastCard: HTMLElement | null = null;
    /** Hover-arrow correlator. Re-attached on every render so it
     *  rebinds against fresh legend rows. */
    private readonly arrows = new LegendArrowController();

    constructor(private readonly config: FocusInspectorConfig) {}

    /**
     * Replace the descriptor list with daemon-advertised products.
     * Pass `null` to fall back to the built-in placeholder set.
     * Re-renders if a card is currently focused.
     */
    setProducts(products: ProductDescriptor[] | null): void {
        this.daemonProducts = products;
        if (this.lastCard) this.render(this.lastCard);
    }

    /** Repaint the inspector for [card], or clear it when [card] is
     *  `null` / earlyFeatures is off. */
    render(card: HTMLElement | null): void {
        const el = this.config.el;
        el.innerHTML = "";
        const visible = !!card && this.config.earlyFeatures();
        el.hidden = !visible;
        if (!visible || !card) {
            this.lastCard = null;
            return;
        }
        const previewId = card.dataset.previewId ?? "";
        const preview = previewId
            ? this.config.getPreview(previewId)
            : undefined;
        if (!previewId || !preview) {
            this.lastCard = null;
            return;
        }
        this.lastCard = card;

        const products = this.resolveProducts();
        const productByKind = new Map(products.map((p) => [p.kind, p]));
        const findings = this.config.getA11yFindings(previewId);
        const scope = this.config.getScope();
        const suggestions = suggestFor({
            preview,
            findingsCount: findings.length,
            mru: this.getMru(scope),
            available: new Set(productByKind.keys()),
        });
        const visibleSuggestions = suggestions.slice(0, MAX_SUGGESTIONS);

        this.suggestedKinds = new Set(visibleSuggestions);

        // Auto-enable cheap suggested kinds, once per scope per kind.
        // Keeps the suggestion chip "active" without requiring a click,
        // but never re-enables a kind the user has manually turned off
        // since the autoEnabled map is sticky for the session.
        if (this.config.autoEnableCheap()) {
            const auto = this.ensureAutoEnabledSet(scope);
            const set = this.ensureEnabledSet(previewId);
            for (const kind of suggestions) {
                if (auto.has(kind)) continue;
                const p = productByKind.get(kind);
                if (!p || p.cost !== "cheap") continue;
                if (!set.has(kind)) {
                    set.add(kind);
                }
                auto.add(kind);
            }
        }

        const inspect = document.createElement("section");
        inspect.className = "focus-panel focus-inspect-panel";
        inspect.appendChild(sectionHeader("search", "Inspect"));
        inspect.appendChild(
            this.renderSuggestionRow(
                visibleSuggestions,
                productByKind,
                preview,
                previewId,
                findings,
            ),
        );
        inspect.appendChild(
            this.renderBuckets(products, preview, previewId, findings),
        );

        const controls = document.createElement("section");
        controls.className = "focus-panel focus-controls-panel";
        controls.appendChild(sectionHeader("settings-gear", "Tools"));
        const toolActions = document.createElement("div");
        toolActions.className = "focus-actions";
        toolActions.appendChild(
            actionButton("eye", "A11y", "Toggle accessibility overlay", () =>
                this.config.onToggleA11yOverlay(),
            ),
        );
        toolActions.appendChild(
            actionButton(
                "device-mobile",
                "Device",
                "Launch on connected Android device",
                () => this.config.onRequestLaunchOnDevice(),
            ),
        );
        toolActions.appendChild(
            actionButton(
                "circle-large-outline",
                "Live",
                "Toggle live preview",
                () => this.config.onToggleInteractive(false),
            ),
        );
        toolActions.appendChild(
            actionButton(
                "record-keys",
                "Record",
                "Record focused preview",
                () => this.config.onToggleRecording(),
            ),
        );
        controls.appendChild(toolActions);

        // Presenter pipeline. Iterate every enabled kind, call its
        // registered presenter (if any), and slot the contributions
        // into the four surfaces: errors banner, card overlay layer,
        // legends section, reports section. Always re-iterate from
        // scratch — presenters are pure factories.
        const presentations = this.collectPresentations(
            card,
            preview,
            previewId,
            findings,
        );
        const errorBanner = this.renderErrors(presentations);
        const legendsSection = this.renderLegends(presentations);
        const reportsSection = this.renderReports(presentations);
        const overlayLayer = this.applyOverlays(card, presentations);

        if (errorBanner) el.appendChild(errorBanner);
        el.appendChild(inspect);
        if (legendsSection) el.appendChild(legendsSection);
        if (reportsSection) el.appendChild(reportsSection);
        el.appendChild(this.historyPanel());
        el.appendChild(controls);

        // Wire legend ↔ overlay correlation last so DOM is settled.
        if (legendsSection) {
            this.arrows.attach(legendsSection, overlayLayer);
        } else {
            this.arrows.detach();
        }
    }

    /**
     * Reset every per-preview product-enable Set. Called by the message
     * dispatcher on the `clearAll` / `setEarlyFeatures off` path so the
     * inspector doesn't carry stale toggles into a new module's
     * previews. Does NOT fire `onToggleDataExtension` callbacks — at
     * this point the daemon has already been told to drop subscriptions
     * by the same teardown path (panel close / module switch /
     * earlyFeatures off), so an extra unsubscribe round-trip would be
     * redundant.
     */
    clearProducts(): void {
        this.enabledByPreview.clear();
        this.autoEnabledByScope.clear();
    }

    /**
     * Focus is moving away from [previewId] (focus-out, focus-mode exit,
     * or panel teardown). Unsubscribe every kind we'd asked the daemon
     * to attach for that preview, then drop the per-preview state so a
     * later return shows fresh checkboxes. Mirrors the a11y-overlay
     * teardown in `FocusController.applyLayout` — once the preview is
     * off-screen the user can't see the data anyway, so keeping the
     * daemon producing it just wastes work.
     *
     * No-op when the previous focus had no enabled kinds, or when the
     * caller hasn't wired `onToggleDataExtension` (tests typically
     * don't).
     */
    releasePreview(previewId: string): void {
        const set = this.enabledByPreview.get(previewId);
        if (!set || set.size === 0) {
            this.enabledByPreview.delete(previewId);
            return;
        }
        const cb = this.config.onToggleDataExtension;
        if (cb) {
            for (const kind of set) {
                cb(previewId, kind, false);
            }
        }
        this.enabledByPreview.delete(previewId);
    }

    private resolveProducts(): ProductDescriptor[] {
        if (this.daemonProducts && this.daemonProducts.length > 0) {
            return this.daemonProducts;
        }
        return BUILT_IN_PRODUCTS;
    }

    private ensureAutoEnabledSet(scope: string): Set<string> {
        let s = this.autoEnabledByScope.get(scope);
        if (!s) {
            s = new Set();
            this.autoEnabledByScope.set(scope, s);
        }
        return s;
    }

    private toggleProduct(kind: string): void {
        const previewId = this.lastCard?.dataset.previewId ?? null;
        if (!previewId) return;
        const set = this.ensureEnabledSet(previewId);
        const turningOn = !set.has(kind);
        if (turningOn) {
            set.add(kind);
        } else {
            set.delete(kind);
        }
        const scope = this.config.getScope();
        const cur = this.getMru(scope);
        const next = bumpMru(cur, kind);
        this.mruByScope.set(scope, next);
        this.config.saveMru(scope, next);
        // Background fetch: tell the extension to subscribe/unsubscribe
        // against the daemon for this (previewId, kind) pair so the next
        // render attaches (or stops attaching) the payload. Skip kinds
        // that have their own dedicated wiring — `local/a11y/overlay`
        // routes through `setA11yOverlay` (handled in `handleChipClick`),
        // and other `local/*` kinds are panel-side only. Re-rendering
        // below paints a placeholder right away so the user sees the
        // toggle take effect even before the daemon answers.
        if (!kind.startsWith("local/") && this.config.onToggleDataExtension) {
            this.config.onToggleDataExtension(previewId, kind, turningOn);
        }
        if (this.lastCard) this.render(this.lastCard);
    }

    private ensureEnabledSet(previewId: string): Set<string> {
        let set = this.enabledByPreview.get(previewId);
        if (!set) {
            set = new Set();
            this.enabledByPreview.set(previewId, set);
        }
        return set;
    }

    private enabledKindsFor(previewId: string): ReadonlySet<string> {
        return this.enabledByPreview.get(previewId) ?? EMPTY_KIND_SET;
    }

    /**
     * Lazy hydrate-and-cache: first read for a scope pulls from the
     * persisted store via `config.loadMru`, subsequent reads stay in
     * memory. Returns the live array reference so callers can pass it
     * into `bumpMru` without copying.
     */
    private getMru(scope: string): readonly string[] {
        const cached = this.mruByScope.get(scope);
        if (cached) return cached;
        const persisted = [...this.config.loadMru(scope)];
        this.mruByScope.set(scope, persisted);
        return persisted;
    }

    private renderSuggestionRow(
        visibleSuggestions: readonly string[],
        productByKind: ReadonlyMap<string, ProductDescriptor>,
        preview: PreviewInfo,
        previewId: string,
        findings: readonly AccessibilityFinding[],
    ): HTMLElement {
        const row = document.createElement("details");
        row.className = "focus-suggestions";
        row.open = this.suggestionsOpen;
        row.addEventListener("toggle", () => {
            this.suggestionsOpen = row.open;
        });
        const summary = document.createElement("summary");
        summary.className = "focus-suggestions-summary";
        const summaryText = document.createElement("span");
        if (visibleSuggestions.length === 0) {
            summaryText.textContent = "No suggestions for this preview";
        } else {
            summaryText.textContent =
                "Suggested for this preview · " + visibleSuggestions.length;
        }
        summary.appendChild(summaryText);
        const chevron = document.createElement("i");
        chevron.className =
            "codicon codicon-chevron-down focus-summary-chevron";
        chevron.setAttribute("aria-hidden", "true");
        summary.appendChild(chevron);
        row.appendChild(summary);

        const chipBox = document.createElement("div");
        chipBox.className = "focus-suggestion-chips";
        for (const kind of visibleSuggestions) {
            const p = productByKind.get(kind);
            if (!p) continue;
            chipBox.appendChild(
                this.renderChip(p, preview, previewId, findings),
            );
        }
        if (visibleSuggestions.length === 0) {
            const empty = document.createElement("span");
            empty.className = "focus-suggestion-empty";
            empty.textContent =
                "Nothing matches this preview's annotations yet — pick layers below.";
            chipBox.appendChild(empty);
        }
        row.appendChild(chipBox);
        return row;
    }

    private renderChip(
        p: ProductDescriptor,
        preview: PreviewInfo,
        previewId: string,
        findings: readonly AccessibilityFinding[],
    ): HTMLElement {
        const chip = document.createElement("button");
        chip.type = "button";
        chip.className = "focus-suggestion-chip";
        const isOn = this.isProductOn(p.kind, previewId);
        chip.dataset.state = isOn ? "on" : "off";
        chip.dataset.cost = p.cost;
        const titleParts = [
            p.label,
            p.cost === "cheap"
                ? "Cheap to enable."
                : "May increase render cost.",
        ];
        chip.title = titleParts.join(" — ");
        const icon = document.createElement("i");
        icon.className = "codicon codicon-" + p.icon;
        icon.setAttribute("aria-hidden", "true");
        chip.appendChild(icon);
        const label = document.createElement("span");
        label.textContent = p.label;
        chip.appendChild(label);
        const hint = this.dynamicHint(p, previewId, findings);
        if (hint) {
            const hintEl = document.createElement("span");
            hintEl.className = "focus-suggestion-chip-hint";
            hintEl.textContent = hint;
            chip.appendChild(hintEl);
        }
        chip.addEventListener("click", () => this.handleChipClick(p));
        // Mark unused params consumed for lint — kept in the signature
        // because future suggestion variants will read them.
        void preview;
        return chip;
    }

    private handleChipClick(p: ProductDescriptor): void {
        if (p.kind === "local/a11y/overlay") {
            this.config.onToggleA11yOverlay();
            return;
        }
        this.toggleProduct(p.kind);
    }

    private renderBuckets(
        products: readonly ProductDescriptor[],
        preview: PreviewInfo,
        previewId: string,
        findings: readonly AccessibilityFinding[],
    ): HTMLElement {
        const wrapper = document.createElement("div");
        wrapper.className = "focus-buckets";
        const grouped = groupByBucket(products);
        const order: ProductBucket[] = [...PRODUCT_BUCKETS];
        // "More" bucket only renders when non-empty — unknown
        // namespaces would otherwise add visual noise on every preview.
        if ((grouped.get("more") ?? []).length > 0) order.push("more");
        for (const bucket of order) {
            const items = grouped.get(bucket) ?? [];
            wrapper.appendChild(
                this.renderBucket(bucket, items, preview, previewId, findings),
            );
        }
        return wrapper;
    }

    private renderBucket(
        bucket: ProductBucket,
        items: readonly ProductDescriptor[],
        preview: PreviewInfo,
        previewId: string,
        findings: readonly AccessibilityFinding[],
    ): HTMLElement {
        const meta = BUCKET_META[bucket];
        const enabledCount = items.filter((p) =>
            this.isProductOn(p.kind, previewId),
        ).length;

        const details = document.createElement("details");
        details.className = "focus-bucket";
        details.dataset.bucket = bucket;
        details.open = !!this.bucketOpen[bucket];
        details.addEventListener("toggle", () => {
            this.bucketOpen[bucket] = details.open;
        });

        const summary = document.createElement("summary");
        summary.className = "focus-bucket-summary";
        const icon = document.createElement("i");
        icon.className = "codicon codicon-" + meta.icon;
        icon.setAttribute("aria-hidden", "true");
        summary.appendChild(icon);
        const label = document.createElement("span");
        label.className = "focus-bucket-label";
        label.textContent = meta.label;
        summary.appendChild(label);
        const count = document.createElement("span");
        count.className = "focus-bucket-count";
        if (items.length === 0) {
            count.textContent = "—";
        } else if (enabledCount > 0) {
            count.textContent = enabledCount + " / " + items.length;
        } else {
            count.textContent = String(items.length);
        }
        summary.appendChild(count);
        const chevron = document.createElement("i");
        chevron.className =
            "codicon codicon-chevron-down focus-summary-chevron";
        chevron.setAttribute("aria-hidden", "true");
        summary.appendChild(chevron);
        details.appendChild(summary);

        const body = document.createElement("div");
        body.className = "focus-bucket-body";
        if (items.length === 0) {
            const empty = document.createElement("div");
            empty.className = "focus-bucket-empty";
            empty.textContent = "No layers from current extensions.";
            body.appendChild(empty);
        }
        // Sort within bucket by MRU rank then label, so familiar
        // layers float to the top without changing the bucket itself.
        const mru = this.getMru(this.config.getScope());
        const mruRank = (kind: string): number => {
            const idx = mru.indexOf(kind);
            return idx === -1 ? Number.MAX_SAFE_INTEGER : idx;
        };
        const suggestedRank = (kind: string): number =>
            this.suggestedKinds.has(kind) ? 0 : Number.MAX_SAFE_INTEGER;
        const sorted = [...items].sort((a, b) => {
            const sa = suggestedRank(a.kind);
            const sb = suggestedRank(b.kind);
            if (sa !== sb) return sa - sb;
            const ra = mruRank(a.kind);
            const rb = mruRank(b.kind);
            if (ra !== rb) return ra - rb;
            return a.label.localeCompare(b.label);
        });
        for (const p of sorted) {
            body.appendChild(
                this.renderProductRow(p, preview, previewId, findings),
            );
        }
        details.appendChild(body);
        return details;
    }

    private renderProductRow(
        p: ProductDescriptor,
        preview: PreviewInfo,
        previewId: string,
        findings: readonly AccessibilityFinding[],
    ): HTMLElement {
        const option = document.createElement("label");
        option.className = "focus-product-option";
        option.dataset.cost = p.cost;
        option.dataset.bucket = bucketOf(p.kind);
        const input = document.createElement("input");
        input.type = "checkbox";
        input.checked = this.isProductOn(p.kind, previewId);
        input.addEventListener("change", () => this.handleChipClick(p));
        option.appendChild(input);
        const icon = document.createElement("i");
        icon.className = "codicon codicon-" + p.icon;
        icon.setAttribute("aria-hidden", "true");
        option.appendChild(icon);
        const auditBadge = this.auditBadgeFor(p, previewId, findings);
        if (auditBadge) {
            const badge = document.createElement("span");
            badge.className = "focus-product-suggested-rank";
            badge.textContent = auditBadge;
            badge.title =
                auditBadge === "?"
                    ? "Audit queued"
                    : `${auditBadge} audit finding${auditBadge === "1" ? "" : "s"}`;
            option.appendChild(badge);
        }
        const text = document.createElement("div");
        text.className = "focus-product-text";
        const name = document.createElement("span");
        name.className = "focus-product-name";
        name.textContent = p.label;
        const val = document.createElement("span");
        val.className = "focus-product-value";
        val.textContent =
            this.dynamicHint(p, previewId, findings) ?? p.hint ?? "";
        text.appendChild(name);
        text.appendChild(val);
        option.appendChild(text);
        void preview;
        return option;
    }

    private auditBadgeFor(
        p: ProductDescriptor,
        previewId: string,
        findings: readonly AccessibilityFinding[],
    ): string | null {
        if (!this.suggestedKinds.has(p.kind)) return null;
        const isOn = this.isProductOn(p.kind, previewId);
        if (p.kind.startsWith("a11y/") || p.kind === "local/a11y/overlay") {
            if (findings.length > 0) return String(findings.length);
            return isOn ? "?" : null;
        }
        const hint = this.dynamicHint(p, previewId, findings) ?? "";
        const match = hint.match(/\d+/);
        if (match) {
            const n = Number(match[0]);
            return n > 0 ? String(n) : null;
        }
        if (isOn && p.daemonBacked) return "?";
        return null;
    }

    /**
     * Walk the enabled set, call each kind's registered presenter,
     * and return a flat list of `{kind, presentation}`. Presenters
     * that aren't registered or return `null` are skipped.
     *
     * Special-cased a11y/recomposition toggles: the existing
     * `local/a11y/overlay` and `compose/recomposition` rows route
     * through dedicated controllers (focus toolbar / live state) and
     * don't have presenters of their own. The presenter layer
     * complements them rather than replacing them.
     */
    private collectPresentations(
        card: HTMLElement,
        preview: PreviewInfo,
        previewId: string,
        findings: readonly AccessibilityFinding[],
    ): { kind: string; presentation: ProductPresentation }[] {
        const nodes = this.config.getA11yNodes(previewId);
        const ctx: PresenterContext = {
            card,
            preview,
            findings,
            nodes,
            data: (kind: string) =>
                this.config.getDataProduct?.(previewId, kind),
        };
        const out: { kind: string; presentation: ProductPresentation }[] = [];
        // The error presenter is implicit: we always invoke it so a
        // render error surfaces even when the user hasn't enabled
        // anything. Other kinds only run when explicitly enabled.
        const errorPresenter = getPresenter("local/render/error");
        if (errorPresenter) {
            const presentation = errorPresenter(ctx);
            if (presentation) {
                out.push({ kind: "local/render/error", presentation });
            }
        }
        const productByKind = new Map(
            this.resolveProducts().map((p) => [p.kind, p]),
        );
        const enabled = this.enabledKindsFor(previewId);
        for (const kind of enabled) {
            const presenter = getPresenter(kind);
            // A registered presenter is authoritative — non-null
            // contributions get slotted in, an intentional null means
            // "I have nothing to add this render" (a11y/atf with no
            // findings is the canonical case; the legend / overlay
            // surfaces are empty by design). Don't fall back to a
            // pending placeholder in that case — it'd surprise the
            // user with a permanent "Loading…" for a kind the daemon
            // already answered.
            if (presenter) {
                const presentation = presenter(ctx);
                if (presentation) {
                    out.push({ kind, presentation });
                }
                continue;
            }
            // Skip purely panel-side kinds — those either own their
            // own UI surface (a11y overlay, render-error banner) or
            // never produce daemon data we'd be waiting for.
            if (kind.startsWith("local/")) continue;
            const descriptor = productByKind.get(kind);
            const data = ctx.data?.(kind);
            // No registered presenter: render a generic payload-dump
            // Report so every subscribed kind surfaces *something*
            // useful — JSON pretty-print for structured data, an
            // <img> for known image payloads. Keeps the panel
            // unsurprising for kinds we haven't yet hand-rolled a
            // presenter for, and gives consumers a flexible "what did
            // the daemon attach?" investigation surface. When no data
            // has arrived yet, fall through to the pending placeholder
            // so the toggle still paints something.
            const generic =
                data !== undefined
                    ? genericPresentation(kind, descriptor?.label, data)
                    : null;
            out.push({
                kind,
                presentation:
                    generic ?? pendingPresentation(kind, descriptor?.label),
            });
        }
        return out;
    }

    private renderErrors(
        presentations: readonly {
            kind: string;
            presentation: ProductPresentation;
        }[],
    ): HTMLElement | null {
        const errors: { kind: string; error: PresentationError }[] = [];
        for (const { kind, presentation } of presentations) {
            if (presentation.error) {
                errors.push({ kind, error: presentation.error });
            }
        }
        if (errors.length === 0) return null;
        const banner = document.createElement("section");
        banner.className = "focus-panel focus-error-panel";
        banner.setAttribute("role", "alert");
        for (const { kind, error } of errors) {
            const row = document.createElement("div");
            row.className = "focus-error-row";
            row.dataset.kind = kind;
            const icon = document.createElement("i");
            icon.className = "codicon codicon-error";
            icon.setAttribute("aria-hidden", "true");
            row.appendChild(icon);
            const body = document.createElement("div");
            body.className = "focus-error-body";
            const title = document.createElement("div");
            title.className = "focus-error-title";
            title.textContent = error.title;
            const message = document.createElement("div");
            message.className = "focus-error-message";
            message.textContent = error.message;
            body.appendChild(title);
            body.appendChild(message);
            if (error.detail) {
                const detail = document.createElement("div");
                detail.className = "focus-error-detail";
                detail.textContent = error.detail;
                body.appendChild(detail);
            }
            row.appendChild(body);
            banner.appendChild(row);
        }
        return banner;
    }

    private renderLegends(
        presentations: readonly {
            kind: string;
            presentation: ProductPresentation;
        }[],
    ): HTMLElement | null {
        const legends: { kind: string; legend: PresentationLegend }[] = [];
        for (const { kind, presentation } of presentations) {
            if (presentation.legend && presentation.legend.entries.length > 0) {
                legends.push({ kind, legend: presentation.legend });
            }
        }
        if (legends.length === 0) return null;
        const section = document.createElement("section");
        section.className = "focus-panel focus-legends-panel";
        section.appendChild(sectionHeader("symbol-key", "Legends"));
        for (const { kind, legend } of legends) {
            const block = document.createElement("div");
            block.className = "focus-legend-block";
            block.dataset.kind = kind;
            const head = document.createElement("div");
            head.className = "focus-legend-head";
            const heading = document.createElement("span");
            heading.className = "focus-legend-title";
            heading.textContent = legend.title;
            head.appendChild(heading);
            if (legend.summary) {
                const sum = document.createElement("span");
                sum.className = "focus-legend-summary";
                sum.textContent = legend.summary;
                head.appendChild(sum);
            }
            block.appendChild(head);
            const list = document.createElement("ul");
            list.className = "focus-legend-list";
            for (const entry of legend.entries) {
                const li = document.createElement("li");
                li.className = "focus-legend-row";
                li.dataset.legendId = entry.id;
                if (entry.level) li.dataset.level = entry.level;
                // tabIndex so keyboard users can step through entries
                // and trigger the same focus → arrow path as hover.
                li.tabIndex = 0;
                const dot = document.createElement("span");
                dot.className = "focus-legend-dot";
                li.appendChild(dot);
                const text = document.createElement("div");
                text.className = "focus-legend-text";
                const lab = document.createElement("div");
                lab.className = "focus-legend-label";
                lab.textContent = entry.label;
                text.appendChild(lab);
                if (entry.detail) {
                    const det = document.createElement("div");
                    det.className = "focus-legend-detail";
                    det.textContent = entry.detail;
                    text.appendChild(det);
                }
                li.appendChild(text);
                list.appendChild(li);
            }
            block.appendChild(list);
            section.appendChild(block);
        }
        return section;
    }

    private renderReports(
        presentations: readonly {
            kind: string;
            presentation: ProductPresentation;
        }[],
    ): HTMLElement | null {
        const reports: { kind: string; report: PresentationReport }[] = [];
        for (const { kind, presentation } of presentations) {
            if (presentation.report) {
                reports.push({ kind, report: presentation.report });
            }
        }
        if (reports.length === 0) return null;
        const section = document.createElement("section");
        section.className = "focus-panel focus-reports-panel";
        section.appendChild(sectionHeader("output", "Data"));
        for (const { kind, report } of reports) {
            const details = document.createElement("details");
            details.className = "focus-report";
            details.dataset.kind = kind;
            const summary = document.createElement("summary");
            summary.className = "focus-report-summary";
            const title = document.createElement("span");
            title.className = "focus-report-title";
            title.textContent = report.title;
            summary.appendChild(title);
            if (report.summary) {
                const sum = document.createElement("span");
                sum.className = "focus-report-summary-hint";
                sum.textContent = report.summary;
                summary.appendChild(sum);
            }
            const chevron = document.createElement("i");
            chevron.className =
                "codicon codicon-chevron-down focus-summary-chevron";
            chevron.setAttribute("aria-hidden", "true");
            summary.appendChild(chevron);
            details.appendChild(summary);
            const body = document.createElement("div");
            body.className = "focus-report-body";
            body.appendChild(report.body);
            details.appendChild(body);
            section.appendChild(details);
        }
        return section;
    }

    /**
     * Stamp every presentation's `overlay` into a stack on the focused
     * card. Existing per-card overlays (`.a11y-overlay`,
     * `.a11y-hierarchy-overlay`) keep painting through their own paths
     * — this layer sits above them so presentation overlays can
     * coexist without z-fighting.
     */
    private applyOverlays(
        card: HTMLElement,
        presentations: readonly {
            kind: string;
            presentation: ProductPresentation;
        }[],
    ): HTMLElement | null {
        const container = card.querySelector<HTMLElement>(".image-container");
        if (!container) return null;
        let stack = container.querySelector<HTMLElement>(
            ".focus-overlay-stack",
        );
        if (!stack) {
            stack = document.createElement("div");
            stack.className = "focus-overlay-stack";
            container.appendChild(stack);
        }
        stack.innerHTML = "";
        let painted = 0;
        for (const { kind, presentation } of presentations) {
            if (!presentation.overlay) continue;
            presentation.overlay.dataset.kind = kind;
            stack.appendChild(presentation.overlay);
            painted += 1;
        }
        if (painted === 0) {
            stack.remove();
            return null;
        }
        return stack;
    }

    private historyPanel(): HTMLElement {
        const history = document.createElement("details");
        history.className = "focus-panel focus-history-panel";
        history.open = this.historyOpen;
        history.addEventListener("toggle", () => {
            this.historyOpen = history.open;
        });
        const summary = document.createElement("summary");
        summary.className = "focus-panel-header focus-history-summary";
        summary.innerHTML =
            '<i class="codicon codicon-history" aria-hidden="true"></i>';
        const label = document.createElement("span");
        label.textContent = "History";
        summary.appendChild(label);
        const chevron = document.createElement("i");
        chevron.className =
            "codicon codicon-chevron-down focus-summary-chevron";
        chevron.setAttribute("aria-hidden", "true");
        summary.appendChild(chevron);
        history.appendChild(summary);
        const historyActions = document.createElement("div");
        historyActions.className = "focus-actions";
        historyActions.appendChild(
            actionButton(
                "git-compare",
                "HEAD",
                "Diff vs last archived render",
                () => this.config.onRequestFocusedDiff("head"),
            ),
        );
        historyActions.appendChild(
            actionButton(
                "source-control",
                "main",
                "Diff vs latest archived main render",
                () => this.config.onRequestFocusedDiff("main"),
            ),
        );
        history.appendChild(historyActions);
        return history;
    }

    private isProductOn(kind: string, previewId: string): boolean {
        if (kind === "local/a11y/overlay") {
            return previewId === this.config.getA11yOverlayId();
        }
        const set = this.enabledKindsFor(previewId);
        if (kind === "compose/recomposition") {
            return this.config.isLive(previewId) || set.has(kind);
        }
        return set.has(kind);
    }

    private dynamicHint(
        p: ProductDescriptor,
        previewId: string,
        findings: readonly AccessibilityFinding[],
    ): string | null {
        if (p.kind === "local/a11y/overlay" && findings.length > 0) {
            return (
                findings.length +
                " finding" +
                (findings.length === 1 ? "" : "s")
            );
        }
        if (p.kind === "layout/tree") {
            const nodes = this.config.getA11yNodes(previewId);
            if (nodes.length > 0) {
                return nodes.length + " node" + (nodes.length === 1 ? "" : "s");
            }
        }
        if (p.kind === "compose/recomposition") {
            return this.config.isLive(previewId) ? "Live" : null;
        }
        return null;
    }
}

function sectionHeader(icon: string, label: string): HTMLElement {
    const header = document.createElement("div");
    header.className = "focus-panel-header";
    header.innerHTML =
        '<i class="codicon codicon-' + icon + '" aria-hidden="true"></i>';
    const span = document.createElement("span");
    span.textContent = label;
    header.appendChild(span);
    return header;
}

/**
 * Shared empty-set sentinel returned from `enabledKindsFor` when a
 * preview has no toggles. Avoids allocating a fresh Set on every render
 * — the inspector re-renders on focus navigation, MRU updates, daemon
 * pushes, and product-list changes.
 */
const EMPTY_KIND_SET: ReadonlySet<string> = new Set();

/**
 * Build a generic Report contribution for a kind that has no registered
 * presenter but does have a cached payload. Two shapes:
 *
 *  - Image payload (`{ imageBase64, mediaType? }`) — render as `<img>`
 *    inside a collapsed `<details>` so the user can scrub through it
 *    without it stealing focus from the structured surfaces.
 *  - Anything else — JSON-stringify the value into a `<pre>` block.
 *
 * Returns `null` if the value is `null`, an empty object, or otherwise
 * obviously empty so the caller can fall back to "no data" messaging.
 * Other readers that want a polished view can register a kind-specific
 * presenter — this is the unsurprising default.
 */
function genericPresentation(
    kind: string,
    label: string | undefined,
    data: unknown,
): ProductPresentation | null {
    if (data === null) return null;
    const display = label && label.length > 0 ? label : kind;
    // Image-shaped payloads — same wire shape `extension.ts` produces
    // for `.png` data products. Treat the payload as opaque bytes; if
    // a `mediaType` isn't set we default to PNG because that's what
    // every binary data product ships today.
    if (typeof data === "object" && data !== null) {
        const maybeImage = data as {
            imageBase64?: unknown;
            mediaType?: unknown;
        };
        if (
            typeof maybeImage.imageBase64 === "string" &&
            maybeImage.imageBase64.length > 0
        ) {
            const mediaType =
                typeof maybeImage.mediaType === "string"
                    ? maybeImage.mediaType
                    : "image/png";
            const body = document.createElement("div");
            body.className = "focus-report-generic focus-report-generic-image";
            const img = document.createElement("img");
            img.className = "focus-report-overlay-img";
            img.alt = display;
            img.src = `data:${mediaType};base64,${maybeImage.imageBase64}`;
            body.appendChild(img);
            return { report: { title: display, body } };
        }
    }
    let serialized: string;
    try {
        serialized = JSON.stringify(data, null, 2);
    } catch {
        // Unserialisable payloads (cycles, BigInt, etc.) — fall back
        // to the runtime stringification rather than dropping the
        // report entirely. The user still sees that data arrived.
        serialized = String(data);
    }
    if (!serialized || serialized === "{}" || serialized === "[]") {
        return null;
    }
    const body = document.createElement("div");
    body.className = "focus-report-generic focus-report-generic-json";
    const pre = document.createElement("pre");
    pre.className = "focus-report-pre";
    pre.textContent = serialized;
    body.appendChild(pre);
    return { report: { title: display, summary: "Daemon data", body } };
}

/**
 * Build a "Loading…" report contribution for a kind the user just
 * enabled, when the registered presenter (or absence of one) hasn't
 * produced anything yet. Keeps the data section non-empty between the
 * click and the next render so the toggle visibly takes effect.
 */
function pendingPresentation(
    kind: string,
    label: string | undefined,
): ProductPresentation {
    const body = document.createElement("div");
    body.className = "focus-report-empty";
    const display = label && label.length > 0 ? label : kind;
    body.textContent = `Waiting for daemon to attach ${display}…`;
    return {
        report: {
            title: display,
            summary: "Loading",
            body,
        },
    };
}

function actionButton(
    icon: string,
    label: string,
    title: string,
    onClick: () => void,
): HTMLElement {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "focus-action";
    btn.title = title;
    btn.innerHTML =
        '<i class="codicon codicon-' + icon + '" aria-hidden="true"></i>';
    const span = document.createElement("span");
    span.textContent = label;
    btn.appendChild(span);
    btn.addEventListener("click", onClick);
    return btn;
}

/**
 * Built-in placeholder set used until the daemon's advertised
 * capabilities are pushed in via `setProducts`. Mirrors the layers the
 * previous flat-picker shipped, classified into buckets by their
 * pseudo-kinds (`local/<bucket-prefix>/...`). Kept here rather than in
 * the taxonomy module because it's UI-shaped (icons, labels) — the
 * taxonomy module is pure data classification.
 */
const BUILT_IN_PRODUCTS: ProductDescriptor[] = [
    {
        kind: "local/a11y/overlay",
        label: "Accessibility overlay",
        icon: "eye",
        hint: "Overlay",
        cost: "expensive",
        daemonBacked: true,
    },
    {
        kind: "layout/tree",
        label: "Layout",
        icon: "list-tree",
        hint: "Tree and bounds",
        cost: "cheap",
        daemonBacked: true,
    },
    {
        kind: "text/strings",
        label: "Strings",
        icon: "symbol-string",
        hint: "Text and translations",
        cost: "cheap",
        daemonBacked: true,
    },
    {
        kind: "resources/used",
        label: "Resources",
        icon: "file-code",
        hint: "Resources used",
        cost: "cheap",
        daemonBacked: true,
    },
    {
        kind: "fonts/used",
        label: "Fonts",
        icon: "text-size",
        hint: "Fonts used",
        cost: "cheap",
        daemonBacked: true,
    },
    {
        kind: "render/trace",
        label: "Render",
        icon: "pulse",
        hint: "Trace and AI trace",
        cost: "expensive",
        daemonBacked: true,
    },
    {
        kind: "compose/theme",
        label: "Theme",
        icon: "symbol-color",
        hint: "Theme tokens",
        cost: "cheap",
        daemonBacked: true,
    },
    {
        kind: "compose/recomposition",
        label: "Recomposition",
        icon: "sync",
        hint: "Heatmap",
        cost: "expensive",
        daemonBacked: true,
    },
];
