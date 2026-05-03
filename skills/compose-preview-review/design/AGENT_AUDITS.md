# Agent PR audits

Use this reference when reviewing a Compose app PR with compose-ai-tools and a
focused audit is needed beyond the basic before/after screenshot diff. Keep
comments evidence-based: cite the preview, locale/device when relevant, and the
data product or screenshot that supports the finding.

### Accessibility audit

Sample command: `compose-preview a11y --filter <screen> --json --fail-on warnings`

Check:

- No new ATF warnings or errors on the changed preview.
- Visible text is readable and consistent with the semantics.
- Labels, roles, actions, and state descriptions make sense for assistive
  technology.
- Touch target warnings are real regressions, not hidden decorative nodes.

### Localisation audit

Sample command: `render_preview uri=<preview-uri> overrides.localeTag=fr-FR`

Check:

- Representative locales render the expected translated copy.
- Missing translations and accidental fallbacks are named concretely.
- Locale-specific resources are selected where expected.
- Long translated strings remain visible and do not overlap adjacent content.

### Wear and round-device audit

Sample command: `get_preview_data uri=<wear-preview-uri> kind=render/deviceClip`

Check:

- TransformingLazyColumn (TLC) content is not clipped at the top or bottom in
  the initial captured state.
- Text bounds and controls stay inside the useful circular area.
- Edge buttons are fully visible, tappable, and not hidden by system chrome.
- Scrollable content does not rely on a first or last item that is only
  partially visible.

### Text overflow and readability audit

Sample command: `get_preview_data uri=<preview-uri> kind=text/strings`

Check:

- Long labels, numbers, and dynamic strings remain legible.
- Text does not overlap icons, controls, or neighboring text.
- Important content is not only present in semantics while visually clipped.
- Font fallback or weight changes are intentional.

### Resource and theme provenance audit

Sample command: `get_preview_data uri=<preview-uri> kind=resources/used`

Check:

- Colors, typography, dimensions, strings, images, and fonts come from the
  expected resources or theme tokens.
- Dark mode, dynamic color, and preview-local overrides do not accidentally
  hide content.
- Changed resources map to visible preview changes, or the absence of visual
  change is explained.

### Visual regression and changed-region audit

Sample command: `compose-preview show --filter <screen> --json --changed-only`

Check:

- Changed regions match the intended UI area.
- Unexpected changed pixels are explained by animation, rendering variance, or
  a real regression.
- New or removed previews are called out separately from changed previews.

### Runtime and recomposition audit

Sample command: `get_preview_data uri=<preview-uri> kind=compose/recomposition`

Check:

- A preview does not become unexpectedly slow or noisy to render.
- Recomposition changes are tied to the relevant component.
- Trace output is treated as triage evidence unless the PR explicitly targets
  runtime behavior.

### Failure triage audit

Sample command: `get_preview_data uri=<preview-uri> kind=test/failure`

Check:

- The failure is reported with the preview id, target, and shortest useful
  error.
- Unavailable data products are distinguished from failing previews.
- Follow-up issues are raised for infrastructure or schema gaps that are too
  complex to fix inside the reviewed PR.
