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
import "./components/CatalogToolbar.js";
import "./components/GroupMemory.js";
import "./components/HistoryMenu.js";
import "./components/InspectLayers.js";
import "./components/PageZoom.js";
import "./components/ParityLanes.js";
import "./components/ParityScores.js";
import "./components/RcLanes.js";
import "./components/SpecCompare.js";
import "./components/ViewerDrawers.js";
// Not a component: installs `window.cpRcFonts` for the legacy lanes to call.
import "./rcFonts.js";
