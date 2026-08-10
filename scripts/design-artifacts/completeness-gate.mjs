/**
 * Decide whether a catalog render is safe to publish.
 *
 * `allowIncomplete` permits a partial catalog, but never a total miss: if at
 * least one required component is missing and no component resolved, publishing
 * would replace the delivery branch with an empty catalog.
 *
 * A spec made entirely of declared sticker-less or deferred entries has no
 * `missing` entries, so it is not mistaken for a failed render here.
 *
 * @returns {"empty" | "incomplete" | null}
 */
export function completenessFailure({
  allowIncomplete,
  resolvedCount,
  missingCount,
  withoutSemanticsCount,
}) {
  if (missingCount > 0 && resolvedCount === 0) return "empty";
  if (!allowIncomplete && (missingCount > 0 || withoutSemanticsCount > 0)) {
    return "incomplete";
  }
  return null;
}
