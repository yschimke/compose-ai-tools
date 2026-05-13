// Unit tests for the UI-Automator selector-snippet builder.
// Lockstep mirror of `HierarchySelectorBuilderTest.kt`.

import * as assert from "assert";
import {
    buildSelectorSnippet,
    type UiaHierarchyNodeLite,
} from "../webview/preview/uiaSelector";

function node(overrides: Partial<UiaHierarchyNodeLite>): UiaHierarchyNodeLite {
    return {
        text: null,
        contentDescription: null,
        testTag: null,
        testTagAncestors: [],
        role: null,
        ...overrides,
    };
}

describe("uiaSelector.buildSelectorSnippet", () => {
    it("prefers a unique testTag", () => {
        const target = node({ testTag: "submit", text: "Submit" });
        const nodes = [target, node({ testTag: "cancel", text: "Cancel" })];
        assert.strictEqual(
            buildSelectorSnippet(target, nodes),
            'By.testTag("submit")',
        );
    });

    it("falls back to text when testTag is missing", () => {
        const target = node({ text: "Submit" });
        const nodes = [target, node({ text: "Cancel" })];
        assert.strictEqual(
            buildSelectorSnippet(target, nodes),
            'By.text("Submit")',
        );
    });

    it("falls back to contentDescription as last single-axis option", () => {
        const target = node({ contentDescription: "Submit form" });
        const nodes = [target, node({ contentDescription: "Cancel form" })];
        assert.strictEqual(
            buildSelectorSnippet(target, nodes),
            'By.desc("Submit form")',
        );
    });

    it("chains nearest disambiguating ancestor when the anchor is non-unique", () => {
        const a = node({
            text: "Item",
            testTagAncestors: ["screen", "list-A"],
        });
        const b = node({
            text: "Item",
            testTagAncestors: ["screen", "list-B"],
        });
        assert.strictEqual(
            buildSelectorSnippet(a, [a, b]),
            'By.text("Item").hasParent(By.testTag("list-A"))',
        );
    });

    it("skips blank ancestor entries during the walk", () => {
        const a = node({
            text: "Item",
            testTagAncestors: ["screen", "", "list-A"],
        });
        const b = node({
            text: "Item",
            testTagAncestors: ["screen", "", "list-B"],
        });
        assert.strictEqual(
            buildSelectorSnippet(a, [a, b]),
            'By.text("Item").hasParent(By.testTag("list-A"))',
        );
    });

    it("returns null when every axis is blank", () => {
        const target = node({});
        assert.strictEqual(buildSelectorSnippet(target, [target]), null);
    });

    it("returns bare anchor when no ancestor disambiguates", () => {
        const a = node({ text: "Item" });
        const b = node({ text: "Item" });
        assert.strictEqual(buildSelectorSnippet(a, [a, b]), 'By.text("Item")');
    });

    it("escapes quotes / newlines in the rendered snippet", () => {
        const target = node({ text: 'Line 1\nLine 2 with "quotes"' });
        assert.strictEqual(
            buildSelectorSnippet(target, [target]),
            'By.text("Line 1\\nLine 2 with \\"quotes\\"")',
        );
    });
});
