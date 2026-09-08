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
        }
      }
    }
  }

  return { errors, warnings };
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
