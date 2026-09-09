import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

import {
  FILE_TEMPLATES,
  STRUCTURAL_ROLES,
  TEMPLATE_DIR,
  UI_BUILDER_POLICY_SCHEMA,
  validatePolicy,
} from "./ui-builder-policy.mjs";

const here = dirname(fileURLToPath(import.meta.url));

const wellFormed = () => ({
  schema: UI_BUILDER_POLICY_SCHEMA,
  platform: "wear",
  platformLabel: "Wear",
  frame: {
    adapter: "frame/round-screen",
    geometry: {
      $comment: "written by ScreenScaffoldContentPaddingTest",
      contentPadding: [
        { screenDp: 192, horizontalDp: 10, verticalDp: 20 },
        { screenDp: 240, horizontalDp: 13, verticalDp: 24 },
      ],
    },
  },
  builtins: { "wear-m3/screen-scaffold": { role: "screen-root" } },
  menu: { groupOrder: ["Layout"] },
});

const codes = (policy) => validatePolicy(policy);

test("a well-formed policy has nothing to say about it", () => {
  assert.deepEqual(codes(wellFormed()), { errors: [], warnings: [] });
});

test("the schema and the platform word are required, and the word is a word", () => {
  const { errors } = codes({ platform: "Wear OS" });
  assert.equal(errors.length, 2);
  assert.match(errors[0], /no "schema"/);
  assert.match(errors[1], /lowercase word/);
});

test("a builtin must name a role the template engine knows", () => {
  const policy = wellFormed();
  policy.builtins = {
    "wear-m3/screen-scaffold": { role: "screen-root" },
    "wear-m3/mystery": { role: "carousel" },
    "wear-m3/nameless": {},
  };

  const { errors } = codes(policy);
  assert.equal(errors.length, 2);
  assert.match(errors[0], /"wear-m3\/mystery" names role "carousel"/);
  assert.match(errors[1], /"wear-m3\/nameless" names no role/);
});

test("prose inside builtins is an error, because it is a parse failure and not a style point", () => {
  // The one place a `$comment` breaks the generator rather than the schema: `builtins` values are
  // typed, so a comment entry decodes as a builtin with no role and the whole file is refused.
  const policy = wellFormed();
  policy.builtins = { $comment: "the only components this file may declare", ...policy.builtins };

  const { errors } = codes(policy);
  assert.equal(errors.length, 1);
  assert.match(errors[0], /parse failure/);
  assert.match(errors[0], /\$comment_builtins/);
});

test("templates and the declared strategy have to agree, and both directions are warnings", () => {
  // Warnings rather than errors: each does something coherent, just not what the author meant, and
  // a catalog in the middle of being authored should stay checkable.
  const declaredButUnused = wellFormed();
  declaredButUnused.code = { strategy: "record", templates: { list: "…" } };
  assert.equal(codes(declaredButUnused).errors.length, 0);
  assert.match(codes(declaredButUnused).warnings[0], /none of them is read/);

  const claimedButAbsent = wellFormed();
  claimedButAbsent.code = { strategy: "templates" };
  assert.match(codes(claimedButAbsent).warnings[0], /which is what "record" means/);
});

test("an unknown template role is an error, and the whole-file templates are not", () => {
  const policy = wellFormed();
  policy.code = {
    strategy: "templates",
    templates: { "screen-root": "…", previews: "…", file: "…", carousel: "…" },
  };

  const { errors } = codes(policy);
  assert.equal(errors.length, 1);
  assert.match(errors[0], /"carousel"/);
});

test("padding rows must ascend, because a reader interpolates between adjacent ones", () => {
  const policy = wellFormed();
  policy.frame.geometry.contentPadding = [
    { screenDp: 240, horizontalDp: 13, verticalDp: 24 },
    { screenDp: 192, horizontalDp: 10, verticalDp: 20 },
  ];

  const { errors } = codes(policy);
  assert.equal(errors.length, 1);
  assert.match(errors[0], /ascending screenDp order/);
});

test("a geometry block with no provenance note is warned about", () => {
  // The block exists because the numbers are measured rather than authored. Saying which test
  // measures them is the whole defence against somebody editing them by hand later.
  const policy = wellFormed();
  delete policy.frame.geometry.$comment;

  const { warnings } = codes(policy);
  assert.equal(warnings.length, 1);
  assert.match(warnings[0], /measured, not authored/);
});

test("a preview surface must say how honest it is, and why when it is not the product", () => {
  const policy = wellFormed();
  policy.previewSurfaces = {
    native: { fidelity: "authoritative", backend: "android" },
    wasm: { fidelity: "approximate" },
    silent: {},
  };

  const { errors } = codes(policy);
  assert.equal(errors.length, 2);
  assert.match(errors[0], /wasm is approximate but gives no reason/);
  assert.match(errors[1], /silent declares no fidelity/);
});

test("a builtin slot's role is the same closed set as the builtin's own", () => {
  const policy = wellFormed();
  policy.builtins["wear-m3/screen-scaffold"].slots = {
    content: { role: "list" },
    grid: { role: "grid" },
    untyped: { acceptedTraits: ["Action"] },
  };

  const { errors } = codes(policy);
  assert.equal(errors.length, 1);
  assert.match(errors[0], /slot "grid" names role "grid"/);
});

test("a component id prefix must end in a slash", () => {
  const policy = wellFormed();
  policy.componentIdPrefix = "m3";

  assert.match(codes(policy).errors[0], /must end in "\/"/);
});

test("the role vocabulary matches the Kotlin the generator actually reads", async () => {
  // Two lists in two languages, and the contract is that a catalog validated here is a catalog the
  // generator accepts. Pinned rather than trusted: they are edited months apart.
  const kotlin = await readFile(
    join(here, "../../screen/generator/src/commonMain/kotlin/ee/schimke/composeai/discovery/UiBuilderPolicy.kt"),
    "utf8",
  );
  const declared = kotlin
    .slice(kotlin.indexOf("UI_BUILDER_STRUCTURAL_ROLES"))
    .match(/setOf\(([^)]*)\)/)[1]
    .split(",")
    .map((entry) => entry.trim().replace(/^"|"$/g, ""))
    .filter(Boolean);

  assert.deepEqual(declared, STRUCTURAL_ROLES);
  // `previews` and `file` are whole-file templates, not node roles, so they are legal template keys
  // and illegal builtin roles. The generator draws the same line; this states it once more where a
  // reader of the validator will see it.
  assert.deepEqual(FILE_TEMPLATES, ["previews", "file"]);
  for (const name of FILE_TEMPLATES) assert.ok(!STRUCTURAL_ROLES.includes(name));
});

test("a templates path outside ui-builder is an error, not a shrug", async () => {
  // The pre-flight exists to catch a typo before a twenty-minute render. `UiBuilderTemplateLookup`
  // silently DROPS a path outside `ui-builder/` — that tree is what the publishing tasks declare as
  // an input — so a validator that accepted one reported "fine" about the exact case it exists to
  // catch, and the published catalog then named a template nothing carries.
  const outside = wellFormed();
  outside.templates = ["designs/wear-list.json"];
  const errors = validatePolicy(outside).errors;
  assert.equal(errors.length, 1);
  assert.match(errors[0], /is not a file under ui-builder\//);

  const inside = wellFormed();
  inside.templates = [`${TEMPLATE_DIR}/designs/wear-list.json`];
  assert.deepEqual(validatePolicy(inside).errors, []);

  // The directory itself is not a design. The lookup requires a FILE, so a bare `ui-builder` can
  // never resolve — exempting it here let the pre-flight pass a catalog naming a template nothing
  // could carry, which is the failure the rule exists to catch let through by its own exception.
  for (const bare of [TEMPLATE_DIR, `${TEMPLATE_DIR}/`]) {
    const directory = wellFormed();
    directory.templates = [bare];
    assert.equal(validatePolicy(directory).errors.length, 1, bare);
  }

  // The rules the runtime enforces and the rules this states have to be the same set, so the
  // constant is read from the Kotlin rather than written twice.
  const lookup = await readFile(
    join(here, "../../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/UiBuilderTemplateLookup.kt"),
    "utf8",
  );
  assert.match(lookup, new RegExp(`UI_BUILDER_DIR: String = "${TEMPLATE_DIR}"`));
});

test("a scalar code.imports is an error, not a shrug", async () => {
  // Gradle decodes `UiBuilderCode.imports` as a `List<String>`, so the scalar form throws and
  // discovery drops the WHOLE policy — after the render. A build-free check that misses the shapes
  // the build refuses is a check for the shapes nobody gets wrong.
  const scalar = wellFormed();
  scalar.code = { strategy: "record", imports: "androidx.compose.foundation.layout.Column" };
  const errors = validatePolicy(scalar).errors;
  assert.equal(errors.length, 1);
  assert.match(errors[0], /"code.imports" is a list/);

  const nonString = wellFormed();
  nonString.code = { strategy: "record", imports: ["ok", 7] };
  assert.equal(validatePolicy(nonString).errors.length, 1);

  const listed = wellFormed();
  listed.code = { strategy: "record", imports: ["androidx.compose.foundation.layout.Column"] };
  assert.deepEqual(validatePolicy(listed).errors, []);
});

test("every field the reader types is checked, not one per round", async () => {
  // The test above this one is the same finding for `code.imports`; `menu.groupOrder` was the next.
  // Both are the same failure — a shape the Kotlin reader cannot deserialize takes the whole policy
  // down after the render — so the fields are swept together rather than ruled one at a time. This
  // case is the sweep's inventory: if a typed field is added to `UiBuilderPolicyFile` and not
  // registered, this is what should have caught it.
  const scalarOrder = wellFormed();
  scalarOrder.menu = { groupOrder: "Components" };
  const errors = validatePolicy(scalarOrder).errors;
  assert.equal(errors.length, 1);
  assert.match(errors[0], /"menu.groupOrder" is "Components"; it is a list of group names/);

  const notAMenu = wellFormed();
  notAMenu.menu = ["Components"];
  assert.equal(validatePolicy(notAMenu).errors.length, 1);

  const badEntry = wellFormed();
  badEntry.menu = { groupOrder: ["Components", 7] };
  assert.equal(validatePolicy(badEntry).errors.length, 1);

  // Every remaining typed field, each on its own, so a missing registration fails here by name
  // rather than by a count that another rule could happen to satisfy.
  for (const [field, value] of [
    ["catalogId", 7],
    ["platformLabel", ["Wear"]],
    ["$comment", 3],
  ]) {
    const policy = wellFormed();
    policy[field] = value;
    const only = validatePolicy(policy).errors;
    assert.equal(only.length, 1, `${field} is unchecked`);
    assert.match(only[0], new RegExp(`the reader decodes it as a string`));
  }

  const language = wellFormed();
  language.code = { strategy: "record", language: 7 };
  assert.equal(validatePolicy(language).errors.length, 1, "code.language is unchecked");

  for (const field of ["displayName", "group", "canvas"]) {
    const policy = wellFormed();
    policy.builtins = { "wear-m3/screen": { role: STRUCTURAL_ROLES[0], [field]: 7 } };
    const only = validatePolicy(policy).errors;
    assert.equal(only.length, 1, `builtin ${field} is unchecked`);
    assert.match(only[0], new RegExp(`has a "${field}"`));
  }

  // And the well-formed shapes still pass, so the sweep did not start rejecting what it types.
  const ordered = wellFormed();
  ordered.menu = { groupOrder: ["Components", "Layout"] };
  ordered.catalogId = "wear-m3";
  assert.deepEqual(validatePolicy(ordered).errors, []);
});
