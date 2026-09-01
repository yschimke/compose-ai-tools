/**
 * Stamp discovery's production-composable signature onto the published catalog component.
 *
 * `@design-parity/catalog-export` deliberately knows nothing about Kotlin. The bundle does: each
 * preview's inferred `PreviewTarget` carries the production composable's ordered value parameters.
 * Keeping this join in the Compose producer means consumers can describe slots and ordinary API
 * values without parsing source or mistaking the zero-argument preview wrapper for the component.
 */
export function applyComponentParameters(manifest, spec, targetsByFunction) {
  const specByComponentId = new Map(
    (spec?.groups ?? []).flatMap((group) =>
      (group.components ?? []).map((component) => [component.componentId, component]),
    ),
  );
  let stamped = 0;
  for (const component of manifest?.components ?? []) {
    const authored = specByComponentId.get(component.componentId);
    const target = authored?.preview ? targetsByFunction?.get(authored.preview) : undefined;
    // An explicit component override often exists to reject an incorrect inference. Its inferred
    // signature is usable only when both sources name the same production composable — the same
    // safety condition Code Connect applies before it emits a call site.
    if (authored?.component && authored.component !== target?.functionName) continue;
    const parameters = target?.parameters;
    if (!Array.isArray(parameters) || parameters.length === 0) continue;
    component.parameters = parameters.map((parameter) => ({
      name: parameter.name,
      type: parameter.type,
      ...(parameter.hasDefault ? { hasDefault: true } : {}),
      ...(parameter.composableSlot ? { composableSlot: true } : {}),
    }));
    stamped += 1;
  }
  return stamped;
}
