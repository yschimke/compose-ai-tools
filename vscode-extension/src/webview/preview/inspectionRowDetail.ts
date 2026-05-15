// Build `<bundle-row-detail>` sections for an Inspection-bundle row.
// One discriminated union (`InspectionNodeRecord`) carries the
// per-kind node payload; this helper switches on `kind` and emits
// the relevant detail sections (Identity / Layout / Modifiers /
// Selectors etc.) per kind.
//
// Pure helper — host wires the body-host delegated click listener
// to call this with the record looked up via
// `InspectionBundleData.nodeById`.

import { html } from "lit";
import type { InspectionNodeRecord } from "./inspectionPresenters";
import type { BundleRowDetailSection } from "./components/BundleRowDetail";

export function buildInspectionRowDetail(
    record: InspectionNodeRecord,
): readonly BundleRowDetailSection[] {
    switch (record.kind) {
        case "compose/semantics":
            return buildSemanticsDetail(record.node);
        case "layout/inspector":
            return buildLayoutDetail(record.node);
        case "uia/hierarchy":
            return buildUiaDetail(record.node);
    }
}

export function inspectionRowTitle(record: InspectionNodeRecord): string {
    switch (record.kind) {
        case "compose/semantics":
            return (
                record.node.label ??
                record.node.text ??
                record.node.testTag ??
                record.node.nodeId
            );
        case "layout/inspector":
            return record.node.component || record.node.nodeId;
        case "uia/hierarchy":
            return (
                record.node.text ??
                record.node.contentDescription ??
                record.node.testTag ??
                "(uia node)"
            );
    }
}

function buildSemanticsDetail(
    node: import("./inspectionPresenters").ComposeSemanticsNode,
): readonly BundleRowDetailSection[] {
    const sections: BundleRowDetailSection[] = [];

    const identity: {
        label: string;
        value: string | import("lit").TemplateResult;
    }[] = [
        { label: "Node id", value: html`<code>${node.nodeId}</code>` },
        { label: "Bounds", value: html`<code>${node.boundsInRoot}</code>` },
    ];
    if (node.role) identity.push({ label: "Role", value: node.role });
    if (node.testTag) {
        identity.push({
            label: "Test tag",
            value: html`<code>${node.testTag}</code>`,
        });
    }
    if (node.mergeMode) {
        identity.push({ label: "Merge mode", value: node.mergeMode });
    }
    sections.push({ heading: "Identity", entries: identity });

    const semText: { label: string; value: string }[] = [];
    if (node.label) semText.push({ label: "Label", value: node.label });
    if (node.text) semText.push({ label: "Text", value: node.text });
    if (semText.length > 0) {
        sections.push({ heading: "Semantics text", entries: semText });
    }

    const flags: { label: string; value: string }[] = [];
    if (node.clickable) flags.push({ label: "Clickable", value: "yes" });
    if (flags.length > 0) {
        sections.push({ heading: "Flags", entries: flags });
    }

    return sections;
}

function buildLayoutDetail(
    node: import("./inspectionPresenters").LayoutInspectorNode,
): readonly BundleRowDetailSection[] {
    const sections: BundleRowDetailSection[] = [];

    const identity: {
        label: string;
        value: string | import("lit").TemplateResult;
    }[] = [
        { label: "Component", value: node.component },
        { label: "Node id", value: html`<code>${node.nodeId}</code>` },
    ];
    if (node.source) {
        identity.push({
            label: "Source",
            value: html`<code>${node.source}</code>`,
        });
    }
    if (node.sourceInfo) {
        identity.push({ label: "Source info", value: node.sourceInfo });
    }
    sections.push({ heading: "Identity", entries: identity });

    const b = node.bounds;
    const layout: { label: string; value: string }[] = [
        {
            label: "Bounds",
            value: `${b.left},${b.top}–${b.right},${b.bottom}`,
        },
    ];
    if (node.size) {
        layout.push({
            label: "Size",
            value: `${node.size.width}×${node.size.height}`,
        });
    }
    if (node.constraints) {
        const c = node.constraints;
        const max = (v: number | null | undefined): string =>
            v == null ? "∞" : v.toString();
        layout.push({
            label: "Constraints",
            value: `min ${c.minWidth}×${c.minHeight} · max ${max(c.maxWidth)}×${max(c.maxHeight)}`,
        });
    }
    sections.push({ heading: "Layout", entries: layout });

    const mods = node.modifiers ?? [];
    if (mods.length > 0) {
        sections.push({
            heading: "Modifiers",
            entries: mods.map((m) => ({
                label: m.name,
                value: m.value ?? "—",
            })),
        });
    }

    return sections;
}

function buildUiaDetail(
    node: import("./inspectionPresenters").UiaHierarchyNode,
): readonly BundleRowDetailSection[] {
    const sections: BundleRowDetailSection[] = [];

    const identity: {
        label: string;
        value: string | import("lit").TemplateResult;
    }[] = [
        {
            label: "Bounds",
            value: html`<code>${node.boundsInScreen}</code>`,
        },
    ];
    if (node.role) identity.push({ label: "Role", value: node.role });
    if (node.testTag) {
        identity.push({
            label: "Test tag",
            value: html`<code>${node.testTag}</code>`,
        });
    }
    if (node.testTagAncestors && node.testTagAncestors.length > 0) {
        identity.push({
            label: "Tag ancestors",
            value: node.testTagAncestors.join(" › "),
        });
    }
    sections.push({ heading: "Identity", entries: identity });

    const content: { label: string; value: string }[] = [];
    if (node.text) content.push({ label: "Text", value: node.text });
    if (node.contentDescription) {
        content.push({
            label: "Description",
            value: node.contentDescription,
        });
    }
    if (content.length > 0) {
        sections.push({ heading: "Content", entries: content });
    }

    const actions = node.actions ?? [];
    if (actions.length > 0) {
        sections.push({
            heading: "Actions",
            entries: [{ label: "Available", value: actions.join(", ") }],
        });
    }

    return sections;
}
