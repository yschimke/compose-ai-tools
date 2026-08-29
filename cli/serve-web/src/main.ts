// Entry point for the serve components bundle (`assets/serve-components.js`).
//
// Every component is imported for its `@customElement` side effect; nothing here
// runs on load. A serve page loads this bundle once and upgrades whichever
// elements its server-rendered markup contains. Absent elements do not run, but
// their code is still part of this bundle; see README.md's bundle trade-off.
//
// Add a component here and cover its server/custom-element contract with a
// behavioural test plus the committed page fixtures. See README.md.

import "./components/BackendBadge.js";
import "./components/BgToggle.js";
import "./components/CatalogLive.js";
import "./components/CatalogToolbar.js";
import "./components/CompareWall.js";
import "./components/DesignPage.js";
import "./components/ElementSelection.js";
import "./components/GroupMemory.js";
import "./components/HistoryMenu.js";
import "./components/InspectLayers.js";
import "./components/PageZoom.js";
import "./components/ParityLanes.js";
import "./components/ParityScores.js";
import "./components/RcLanes.js";
import "./components/ReferenceCompare.js";
import "./components/ReportClassification.js";
import "./components/RevisionRuns.js";
import "./components/SpecCompare.js";
import "./components/ViewerDrawers.js";
// Not a component: a global the legacy scripts call. `window.cpRcFonts` makes the page's
// registered faces paintable before a Remote Compose lane paints, and its callers — the inline
// doc-player script among them — are still outside this package.
//
// `window.cpViewerQuery` used to sit beside it, holding the render-URL rules for `viewer.js` to
// reach. It is gone: `viewer.js` is generated from `src/viewer.ts` now and imports those rules
// directly, so the only caller the global ever had no longer needs a window to reach them.
import "./rcFonts.js";
