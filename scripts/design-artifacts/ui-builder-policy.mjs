// `ui-builder.policy.json` validation — structural, build-free, and shaped for the person who is
// authoring one rather than for a machine that has already been handed a good file.
//
// The authoritative reader is `UiBuilderCatalogs.generate` in the Gradle plugin's discovery task,
// which produces `ui-builder.json` and reports what it noticed as `diagnostics` inside the
// published file. This is the pre-flight: a policy file is authored by hand, the first thing that
// reads it is a ~90-minute Design Artifacts render, and a misspelt `platfrom` is a bad way to spend
// an evening.
//
// It is not a JSON Schema validator, deliberately. `ui-builder.policy.schema.json` is the
// contract and the thing an editor autocompletes against; a second, hand-rolled implementation of
// every keyword in it would be the kind of duplicate that drifts. What this checks is the subset a
// schema states poorly or not at all — that a builtin names a role the engine knows, that templates
// and the declared strategy agree, that a comment has not been written where a typed value is
// expected — plus the handful of shapes whose absence produces a confusing failure much later.
//
// Pure library (node built-ins only), so its tests run without `npm ci`. The CLI wrapper is
// validate-ui-builder-policy.mjs.

/** The `schema` value this validator and the generator understand. */
export const UI_BUILDER_POLICY_SCHEMA = "compose-ui-builder-policy/v1";

/**
 * The structural roles the template engine knows.
 *
 * Mirrors `UI_BUILDER_STRUCTURAL_ROLES` in
 * screen/generator/src/commonMain/kotlin/ee/schimke/composeai/discovery/UiBuilderPolicy.kt, and a
 * test pins the two lists to each other. Closed on purpose: the point of templates-as-data over an
 * emitter-as-a-jar is that a builder can validate what a catalog asks for.
 */
export const STRUCTURAL_ROLES = [
  "screen-root",
  "list",
  "list-item",
  "overlay",
  "controlled",
  "decoration",
];

/**
 * The one directory a `templates` path may live under.
 *
 * Mirrors `UI_BUILDER_DIR` in
 * gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/UiBuilderTemplateLookup.kt, which is the
 * code that decides at publish time whether a design is carried.
 */
export const TEMPLATE_DIR = "ui-builder";

/** Whole-file templates, which are not node roles and are legal keys in `code.templates`. */
export const FILE_TEMPLATES = ["previews", "file"];

const isObject = (value) =>
  value !== null && typeof value === "object" && !Array.isArray(value);

const isCommentKey = (key) => key.startsWith("$comment");

/**
 * Validate a parsed policy document.
 *
 * Returns `{ errors, warnings }`, both arrays of strings. An **error** is something that will not do
 * what the author meant — a missing platform, a builtin with no role, a comment written where a
 * typed value goes. A **warning** is something that works but is probably not intended, and the
 * distinction is load-bearing: the CLI exits non-zero on errors only, because a catalog in the
 * middle of being authored should be checkable without being finished.
 */
export function validatePolicy(policy) {
  const errors = [];
  const warnings = [];

  if (!isObject(policy)) {
    return { errors: ["the policy is not a JSON object"], warnings };
  }

  if (policy.schema !== UI_BUILDER_POLICY_SCHEMA) {
    errors.push(
      policy.schema === undefined
        ? `no "schema" — a policy declares "${UI_BUILDER_POLICY_SCHEMA}" so a reader can refuse a major it does not know`
        : `"schema" is ${JSON.stringify(policy.schema)}; this generator writes ${UI_BUILDER_POLICY_SCHEMA}`,
    );
  }

  if (typeof policy.platform !== "string" || policy.platform.length === 0) {
    errors.push('no "platform" — the word catalogs are grouped by; equality is compatibility');
  } else if (!/^[a-z0-9][a-z0-9-]*$/.test(policy.platform)) {
    errors.push(
      `"platform" is ${JSON.stringify(policy.platform)}; it is a lowercase word (mobile, wear, remote-compose), not a label`,
    );
  }

  if (policy.componentIdPrefix !== undefined && !/^[a-z0-9][a-z0-9-]*\/$/.test(policy.componentIdPrefix)) {
    errors.push(
      `"componentIdPrefix" is ${JSON.stringify(policy.componentIdPrefix)}; it prefixes a derived builder id and must end in "/" (e.g. "m3/")`,
    );
  }

  validateTypedShapes(policy, errors);
  validateSurfaces(policy.previewSurfaces, errors);
  validateBuiltins(policy.builtins, errors, warnings);
  validateCode(policy.code, errors, warnings);
  validateFrame(policy.frame, errors, warnings);

  if (policy.templates !== undefined) {
    if (!Array.isArray(policy.templates)) {
      errors.push('"templates" is a list of branch-relative design paths');
    } else {
      for (const entry of policy.templates) {
        if (typeof entry !== "string") {
          errors.push(`"templates" contains ${JSON.stringify(entry)}, which is not a path`);
        } else if (entry.startsWith("/") || entry.includes("..")) {
          errors.push(`"templates" entry ${JSON.stringify(entry)} is not branch-relative`);
        } else if (!entry.startsWith(`${TEMPLATE_DIR}/`) || entry === `${TEMPLATE_DIR}/`) {
          // The same prefix `UiBuilderTemplateLookup` enforces at publish time, stated here so the
          // two agree. It drops a path outside `ui-builder/` silently — that tree is what the tasks
          // declare as an input, so a design anywhere else would be read from somewhere Gradle is
          // not watching — and this validator accepting one meant the build-free pre-flight passed,
          // the twenty-minute render ran, and the published catalog named a template neither
          // discovery nor bundling carries. A pre-flight whose rules are a subset of the runtime's
          // reports "fine" about exactly the cases it exists to catch.
          // The bare directory was exempted here and can never resolve: the lookup requires a
          // FILE, so `"ui-builder"` passed the pre-flight and then named a template neither
          // discovery nor bundling could carry — the exact failure this rule was added to catch,
          // let through by the rule's own exception.
          errors.push(
            `"templates" entry ${JSON.stringify(entry)} is not a file under ${TEMPLATE_DIR}/, so nothing will carry it`,
          );
        }
      }
    }
  }

  return { errors, warnings };
}

/**
 * Every field the Kotlin reader decodes into a TYPE, checked to be that type.
 *
 * These are the expensive failures, and the reason they get a sweep of their own rather than a rule
 * each. A field held as `JsonElement` — `previewSurfaces`, `frame`, `colorTokens`, `assetRegistry` —
 * belongs to the preview server, so a wrong shape there is somebody else's diagnostic and this file
 * only ever checks it structurally. A field with a Kotlin type is different: a wrong shape is a
 * *deserialization* failure, which takes the whole policy file down, so discovery omits
 * `ui-builder.json` entirely — and it does that AFTER the twenty-minute render, having said nothing
 * beforehand. This pre-flight exists to be the cheap half of that.
 *
 * `menu` was the field that showed it: a `"groupOrder": "Components"` reached the Kotlin reader,
 * which decodes `List<String>`, and nothing here said a word. Adding a rule for `menu` alone would
 * have left `catalogId`, `platformLabel`, `code.language` and a builtin's `displayName` / `group` /
 * `canvas` to be found one at a time, each after a render, each its own round. They are declared
 * together instead, so a new typed field in `UiBuilderPolicyFile` has one obvious place to be
 * registered rather than four scattered ones to be forgotten in.
 */
function validateTypedShapes(policy, errors) {
  const strings = [
    // The Kotlin property is `jsonSchema`, but `@SerialName` means the JSON key is `$schema` — the
    // key is what a policy author writes, so the key is what is read here.
    ["$schema", policy["$schema"]],
    ["$comment", policy["$comment"]],
    ["catalogId", policy.catalogId],
    ["platformLabel", policy.platformLabel],
    ["code.language", isObject(policy.code) ? policy.code.language : undefined],
  ];
  for (const [path, value] of strings) {
    if (value !== undefined && typeof value !== "string") {
      errors.push(`"${path}" is ${JSON.stringify(value)}; the reader decodes it as a string`);
    }
  }
  if (isObject(policy.builtins)) {
    for (const [id, builtin] of Object.entries(policy.builtins)) {
      if (!isObject(builtin)) continue;
      for (const field of ["displayName", "group", "canvas"]) {
        const value = builtin[field];
        if (value !== undefined && typeof value !== "string") {
          errors.push(
            `builtin ${JSON.stringify(id)} has a "${field}" of ${JSON.stringify(value)}; the reader decodes it as a string`,
          );
        }
      }
    }
  }
  validateMenu(policy.menu, errors);
}

function validateMenu(menu, errors) {
  if (menu === undefined) return;
  if (!isObject(menu)) {
    errors.push('"menu" is an object; the only thing authored in it is "groupOrder"');
    return;
  }
  const order = menu.groupOrder;
  if (order === undefined) return;
  if (!Array.isArray(order)) {
    // The shape that motivated the sweep. A bare string is the natural mistake, because one group
    // order reads like one value, and the reader decodes `List<String>`.
    errors.push(
      `"menu.groupOrder" is ${JSON.stringify(order)}; it is a list of group names, in the order the shelves appear`,
    );
    return;
  }
  for (const entry of order) {
    if (typeof entry !== "string") {
      errors.push(`"menu.groupOrder" contains ${JSON.stringify(entry)}, which is not a group name`);
    }
  }
}

function validateSurfaces(surfaces, errors) {
  if (surfaces === undefined) return;
  if (!isObject(surfaces)) {
    errors.push('"previewSurfaces" is an object keyed by surface name');
    return;
  }
  for (const [name, surface] of Object.entries(surfaces)) {
    if (isCommentKey(name)) continue;
    if (!isObject(surface)) {
      errors.push(`previewSurfaces.${name} is not an object`);
      continue;
    }
    const fidelity = surface.fidelity;
    if (!["authoritative", "approximate", "unsupported"].includes(fidelity)) {
      // A surface entry that does not say how honest it is tells a consumer less than no entry
      // at all: absent means "nobody claimed anything", present-and-silent looks like a claim.
      errors.push(
        `previewSurfaces.${name} declares no fidelity (authoritative, approximate, unsupported)`,
      );
      continue;
    }
    if (fidelity !== "authoritative" && !surface.reason) {
      // The person this field is for is looking at a fuzzy preview and wondering what about it is
      // fuzzy. Nothing downstream can supply that answer, so it is insisted on here.
      errors.push(
        `previewSurfaces.${name} is ${fidelity} but gives no reason; say what about it is not the product`,
      );
    }
  }
}

function validateBuiltins(builtins, errors, warnings) {
  if (builtins === undefined) return;
  if (!isObject(builtins)) {
    errors.push('"builtins" is an object keyed by builder component id');
    return;
  }
  for (const [id, builtin] of Object.entries(builtins)) {
    if (isCommentKey(id)) {
      // The one place a `$comment` is a parse failure rather than a schema one: `builtins` values
      // are typed, so a comment entry decodes as a builtin with no role and the generator refuses
      // the whole file. Put it beside the field instead, as `$comment_builtins`.
      errors.push(
        `"builtins" carries a ${JSON.stringify(id)} entry. Its values are typed, so prose here is a parse failure — move it to a top-level "$comment_builtins".`,
      );
      continue;
    }
    if (!isObject(builtin)) {
      errors.push(`builtin ${JSON.stringify(id)} is not an object`);
      continue;
    }
    if (typeof builtin.role !== "string") {
      errors.push(
        `builtin ${JSON.stringify(id)} names no role. A builtin exists because it has no call site, and the role is what tells the template engine which template writes it.`,
      );
    } else if (!STRUCTURAL_ROLES.includes(builtin.role)) {
      errors.push(
        `builtin ${JSON.stringify(id)} names role ${JSON.stringify(builtin.role)}; known roles are ${STRUCTURAL_ROLES.join(", ")}`,
      );
    }
    if (builtin.slots !== undefined && !isObject(builtin.slots)) {
      errors.push(`builtin ${JSON.stringify(id)} has a "slots" that is not an object`);
    } else if (isObject(builtin.slots)) {
      for (const [slot, spec] of Object.entries(builtin.slots)) {
        if (!isObject(spec) || spec.role === undefined) continue;
        // The same closed set the builtin's own role uses. A role the engine does not know selects
        // no template, and it should fail where somebody is editing the policy rather than during
        // an export weeks later.
        if (!STRUCTURAL_ROLES.includes(spec.role)) {
          errors.push(
            `builtin ${JSON.stringify(id)} slot ${JSON.stringify(slot)} names role ${JSON.stringify(spec.role)}; known roles are ${STRUCTURAL_ROLES.join(", ")}`,
          );
        }
      }
    }
  }
}

function validateCode(code, errors, warnings) {
  if (code === undefined) return;
  if (!isObject(code)) {
    errors.push('"code" is an object');
    return;
  }
  const strategy = code.strategy ?? "record";
  if (strategy !== "record" && strategy !== "templates") {
    errors.push(`"code.strategy" is ${JSON.stringify(strategy)}; it is "record" or "templates"`);
  }
  // `imports` is decoded by Gradle as a `List<String>`, and a scalar is the easy thing to write —
  // `"imports": "androidx.foo.Bar"`. The decode throws, discovery drops the WHOLE policy and writes
  // no `ui-builder.json`, and this pre-flight reported the policy valid on the way past. A
  // build-free check that misses the shapes the build refuses is a check for the shapes nobody
  // gets wrong.
  const imports = code.imports;
  if (imports !== undefined) {
    if (!Array.isArray(imports)) {
      errors.push('"code.imports" is a list of import lines, not a single string');
    } else {
      for (const line of imports) {
        if (typeof line !== "string") {
          errors.push(`"code.imports" contains ${JSON.stringify(line)}, which is not an import`);
        }
      }
    }
  }
  const templates = code.templates;
  if (templates !== undefined && !isObject(templates)) {
    errors.push('"code.templates" is an object keyed by role');
    return;
  }
  const roles = Object.keys(templates ?? {}).filter((key) => !isCommentKey(key));
  for (const role of roles) {
    if (!STRUCTURAL_ROLES.includes(role) && !FILE_TEMPLATES.includes(role)) {
      errors.push(
        `"code.templates" names role ${JSON.stringify(role)}; known roles are ${STRUCTURAL_ROLES.join(", ")}, plus ${FILE_TEMPLATES.join(" and ")}`,
      );
    }
    if (typeof templates[role] !== "string") {
      errors.push(`"code.templates.${role}" is not a string`);
    }
  }
  if (strategy === "templates" && roles.length === 0) {
    warnings.push(
      '"code.strategy" is "templates" but none are declared, so every node falls back to a record call site — which is what "record" means',
    );
  }
  if (strategy !== "templates" && roles.length > 0) {
    warnings.push(
      `"code.templates" declares ${roles.length} template(s) but "code.strategy" is ${JSON.stringify(strategy)}, so none of them is read`,
    );
  }
}

function validateFrame(frame, errors, warnings) {
  if (frame === undefined) return;
  if (!isObject(frame)) {
    errors.push('"frame" is an object');
    return;
  }
  if (typeof frame.adapter !== "string" || frame.adapter.length === 0) {
    errors.push('"frame" names no adapter (frame/rect, frame/round-screen, frame/widget-host)');
  }
  const geometry = frame.geometry;
  if (geometry === undefined) return;
  if (!isObject(geometry)) {
    errors.push('"frame.geometry" is an object');
    return;
  }
  // The one rule about this block that a schema cannot state, and the reason the block exists at
  // all: it is written by the test that measures it, so it should carry a note saying which one.
  // A warning rather than an error — a catalog mid-authoring should still be checkable — but it is
  // the check most worth having, because the failure it prevents is a hand-copied number that
  // looks measured.
  const hasProvenance = Object.keys(geometry).some(isCommentKey);
  if (!hasProvenance) {
    warnings.push(
      '"frame.geometry" carries no "$comment" naming the test that writes it. These numbers are measured, not authored; say where, or the next reader will edit them by hand.',
    );
  }
  if (geometry.contentPadding !== undefined) {
    if (!Array.isArray(geometry.contentPadding)) {
      errors.push('"frame.geometry.contentPadding" is a list of per-screen-size rows');
    } else {
      const sizes = [];
      for (const row of geometry.contentPadding) {
        if (!isObject(row) || typeof row.screenDp !== "number") {
          errors.push(
            `"frame.geometry.contentPadding" row ${JSON.stringify(row)} has no numeric screenDp`,
          );
          continue;
        }
        sizes.push(row.screenDp);
      }
      const sorted = [...sizes].sort((a, b) => a - b);
      if (sizes.join() !== sorted.join()) {
        // A reader interpolates between adjacent rows. Out of order, it interpolates backwards and
        // produces padding for a size nobody measured, silently.
        errors.push(
          `"frame.geometry.contentPadding" rows are not in ascending screenDp order (${sizes.join(", ")}); a reader interpolates between adjacent rows`,
        );
      }
      if (new Set(sizes).size !== sizes.length) {
        errors.push('"frame.geometry.contentPadding" has two rows for one screenDp');
      }
    }
  }
}
