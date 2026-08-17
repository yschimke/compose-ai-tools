# Component browser mode checklist

This is the proposed scope for a simpler, Storybook-like component browser mode.

Checked items are the recommended defaults. Unchecked items remain available in the full interface.

## Mode-wide behavior

- [x] Keep the mode read-only and focused on browsing
- [x] Discover every preview-bearing Gradle module without requiring `--module`
- [x] Detect and build compatible CMP Wasm browser targets without requiring `--wasm-dir`
  Compatible apps opt in by packaging `compose-preview-components.json` beside `index.html`; the
  marker promises that `?id=<component>` selects the requested component. Automatically discovered
  local distributions remain behind the browse session token, including their relative assets.
- [x] Sticky global `Catalog / Dev mode` toggle in the header
  The choice is the visitor's mode, remembered in the `cp_chrome` cookie the server reads on every
  request, so no link carries it. `?chrome=catalog|dev` remains as a permalink for one request.
- [x] Use human-readable, complete component names throughout
- [x] Preserve catalog → category → group → component → variant hierarchy
- [x] Keep URLs shareable for catalogs, components, and variants
- [x] Remember the selected theme and navigation state
- [x] Use responsive layouts for desktop, tablet, and mobile
- [ ] Show global settings
- [ ] Show keyboard-command onboarding
- [ ] Show authentication or administrative controls
- [ ] Show build version and technical footer information

## Catalog browser

The page for choosing between available catalogs or design systems.

- [x] Catalog cards with representative visual previews
- [x] Catalog display name
- [x] Short catalog description or library name
- [x] Group catalogs by publisher or product family
- [x] Search catalogs by name
- [ ] Show the number of components in each catalog
- [x] Make the entire catalog card clickable
- [x] Clear empty state when no catalogs match
- [ ] Show the catalog's technical identifier
- [ ] Show preview counts separately from component counts
- [ ] Show catalog view counts
- [ ] Show trust status when the catalog is healthy
- [x] Show a warning when a catalog is untrusted or unavailable
- [x] Show repository or provenance details
- [ ] Show GitHub sign-in
- [ ] Show playground access

## Single catalog page

The visual inventory and main navigation for one catalog.

- [x] Breadcrumb back to the catalog browser
- [x] Catalog name and short description
- [x] Persistent, searchable component navigation
- [x] Organize components by category, family, and group
- [x] Show complete component names in navigation
- [x] Show component variants nested beneath their component
- [x] Visual thumbnail grid
- [x] One primary card per component
- [x] Component name beneath each thumbnail
- [x] Make the entire component card clickable
- [x] Search both component names and variant names
- [x] Clear "no matching components" state
- [x] Theme selector when multiple themes are available
- [x] Preserve the selected theme when opening a component
- [ ] Show render failures as a simple unavailable/error card
- [ ] Show technical preview IDs on cards
- [ ] Show separate cards for every state and property combination
- [ ] Show design-file pages alongside components
- [ ] Show view counts
- [ ] Show issue badges
- [ ] Long-press cards to start live sessions
- [ ] Transparent-background control
- [ ] Compare SVG action
- [ ] Compare renderers action
- [ ] Compare with Figma/design-reference action
- [ ] Design-parity dashboard
- [ ] Playground action
- [ ] Download the entire catalog
- [ ] Catalog provenance and generation details
- [ ] Catalog revision controls
- [ ] Refresh or regenerate catalog actions

## Single component page

The focused Storybook-like component viewing experience.

### Identity and navigation

- [x] Full breadcrumb: catalog → category/group → component → variant
- [x] Complete component name as the primary heading
- [x] Current variant or state shown clearly beneath the name
- [x] Persistent searchable component sidebar on larger screens
- [x] Collapsible component navigation on smaller screens
- [x] Previous and next component navigation
- [x] Variant and state navigation grouped beneath the component
- [x] Preserve theme and browsing position between components
- [ ] Show the internal preview ID prominently
- [ ] Show component view counts
- [ ] Show trust badges unless there is a warning

### Main content

- [x] Preview tab
- [x] Sample source-code tab
- [x] Large, visually dominant preview stage
- [x] Copy sample code button
- [x] Syntax-highlighted, selectable source code
- [x] Loading, unavailable, and render-error states
- [x] Fit preview to the available space
- [x] Fit-width/full-size toggle
- [x] Theme selector
- [x] Background selector: light, dark, and transparent
- [ ] Interactive preview automatically when a CMP Wasm renderer is available
  Withdrawn: the page opens on the catalog's baked snapshot, the same rendering Dev mode shows.
  Auto-entering the in-browser app made the published artifact the one thing the browser never
  displayed, and it bypassed the viewer's "wait for the snapshot to land" gate — which cancelled
  the in-flight render and left every component page blank (#4091). `?mode=wasm` still pins the
  lane, and Dev mode carries the renderer combo.
- [x] Authored component controls such as text, boolean, enum, and numeric properties
- [x] Motion tab or control when the component publishes a motion sample
- [ ] Automatically start CMP Wasm live mode, with the baked snapshot as its loading/error fallback
  Withdrawn with the item above: the snapshot is the default rendering, not the fallback.
- [ ] Automatically start recorded animations
- [ ] Renderer/backend selector
- [ ] Backend status badge
- [ ] Remote Compose player selection
- [ ] Raw SVG view
- [ ] Exploded SVG view
- [ ] Design-spec/Figma comparison
- [ ] Pixel diff, triptych, or comparison slider
- [ ] Render-history timeline
- [ ] Published revision selector

### Responsive and environment controls

- [x] Small set of named viewport/device presets for screen components
- [x] Orientation control for screen components
- [ ] Arbitrary fixed, minimum, and maximum dimensions
- [x] Locale override
- [x] Font-scale override
- [ ] Platform-specific environment overrides
- [ ] Low-level Remote Compose value controls

### Inspection and developer tools

- [ ] Accessibility inspection overlay
- [ ] Typography inspection overlay
- [ ] Theme-attribute inspection overlay
- [ ] Touch visualization
- [ ] Gesture hints
- [ ] Scroll and full-page capture controls
- [x] PNG/SVG export links
- [ ] Executable component-bundle download
- [ ] GitHub source-file link
- [ ] Figma source-node link
- [ ] Report-an-issue action
- [ ] Open sample in playground
- [ ] Snapshot/degradation diagnostics

## Intended focus

The streamlined mode should center on three actions:

1. Find a component.
2. Inspect its visual variants.
3. See the sample code required to use it.
