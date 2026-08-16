// Entry point for the serve components bundle (`assets/serve-components.js`).
//
// Every component is imported for its `@customElement` side effect; nothing here
// runs on load. A serve page loads this bundle once and gets whichever elements
// its server-rendered markup actually contains — an element with no tag on the
// page costs its bytes and nothing else.
//
// Add a component by porting one legacy `assets/*.js` file at a time and adding
// its import here, so each step is a diffable change to the committed bundle with
// the page fixtures as the regression net. See README.md.

import "./components/BackendBadge.js";
import "./components/BgToggle.js";
import "./components/CatalogLive.js";
import "./components/CatalogToolbar.js";
import "./components/CompareWall.js";
import "./components/DesignPage.js";
import "./components/GroupMemory.js";
import "./components/HistoryMenu.js";
import "./components/InspectLayers.js";
import "./components/PageZoom.js";
import "./components/ParityLanes.js";
import "./components/ParityScores.js";
import "./components/RcLanes.js";
import "./components/ReferenceCompare.js";
import "./components/SpecCompare.js";
import "./components/ViewerDrawers.js";
// Not components: globals the legacy IIFEs call. `window.cpRcFonts` makes the page's registered
// faces paintable before a Remote Compose lane paints; `window.cpViewerQuery` holds the rules that
// decide what lands in a render URL, the first slice of `viewer.js` to move.
import "./rcFonts.js";
import "./viewerQuery.js";
